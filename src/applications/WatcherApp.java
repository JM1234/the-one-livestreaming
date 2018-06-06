package applications;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

import core.Application;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import fragmentation.Fragment;
import fragmentation.SADFragmentation;
import report.StreamAppReporter;
import routing.TVProphetRouterV2;
import streaming.Stream;
import streaming.StreamChunk;

public class WatcherApp extends StreamingApplication{
	
	public static final String WATCHER_TYPE = "watcherType"; 
	public static final String WAITING_THRESHOLD = "waitingThreshold";
	public static final String PREBUFFER = "prebuffer";
	public static final String MAX_PENDING_REQUEST = "maxPendingRequest";
	
	public static final int PLAYING = 1;
	public static final int WAITING = 0;

	private static DTNHost broadcasterAddress;
	private static int maxPendingRequest;
	private static int waitingThreshold;
	private static int prebuffer;
	
	private int 	status =-1;
	private int 	watcherType; //1 if listener. 0 if just a hop
	private double lastTimePlayed=0;
	private double lastChokeInterval = 0;
	private double lastOptimalInterval=0;
	private double streamStartTime;
	private boolean isWatching=false;
	
	private HashMap<DTNHost, ArrayList<Integer>> neighborData;
	private HashMap<Integer, Double> chunkRequest; //request of this node
//	private HashMap<DTNHost, ArrayList<Message>> receivedRequests;
	private ArrayList<DTNHost> availableNeighbors; //neighbors that we can request from

	private ArrayList<DTNHost> holdHost;
	private ArrayList<DTNHost> prevUnchoked;
	private ArrayList<Integer> holdChunk;
	
	private Stream stream;
	private SADFragmentation sadf;
	private Random r;

	public WatcherApp(Settings s) {
		super(s);
	
		watcherType = s.getInt(WATCHER_TYPE);
		waitingThreshold = s.getInt(WAITING_THRESHOLD);
		prebuffer = s.getInt(PREBUFFER);
		
		holdHost = new ArrayList<DTNHost>();
		prevUnchoked = new ArrayList<DTNHost>();
		holdChunk = new ArrayList<Integer>();
		
		chunkRequest = new HashMap<Integer, Double>();
		neighborData = new HashMap<DTNHost, ArrayList<Integer>>();
		availableNeighbors = new ArrayList<DTNHost>();
		
		sadf = new SADFragmentation();
		r = new Random();
		initUnchoke();
	}
	
	public WatcherApp(WatcherApp a) {
		super(a);
		
		watcherType = a.getWatcherType();
		waitingThreshold = a.getWaitingThreshold();
		prebuffer = a.getPrebuffer();
		
		holdHost = new ArrayList<DTNHost>();
		prevUnchoked = new ArrayList<DTNHost>();
		holdChunk = new ArrayList<Integer>();
		
		chunkRequest = new HashMap<Integer, Double>();
		neighborData = new HashMap<DTNHost, ArrayList<Integer>>();
		availableNeighbors = new ArrayList<DTNHost>();
	
		sadf = new SADFragmentation();
		r = new Random();
		initUnchoke();

	}

	@Override
	public Message handle(Message msg, DTNHost host) {
		String type = (String) msg.getProperty("type");
		if (type==null) return msg;
	
		if (type.equals(APP_TYPE)){
			String msg_type = (String) msg.getProperty("msg_type");
			
			if (msg_type.equals(BROADCAST_LIVE)){
//				System.out.println(host + " received broadcast_live " + msg.getFrom());
				if (!isWatching && watcherType==1){
					isWatching=true;
					status = WAITING;
					sendEventToListeners(StreamAppReporter.INTERRUPTED, 0, host);
					
					String streamID=(String) msg.getProperty("streamID");
					double timeStarted = (double) msg.getProperty("time_started");
					double byterate = (double) msg.getProperty(BYTERATE);
					int durationPerChunk = (int) msg.getProperty(DURATION_PER_CHUNK);
					int noOfChunksPerFrag = (int) msg.getProperty(CHUNKS_PER_FRAG);
					int windowSize = (int) msg.getProperty(WINDOW_SIZE);
							
					maxPendingRequest = 31250 / (int) byterate;
					this.stream = new Stream(streamID, durationPerChunk, byterate, timeStarted, windowSize);
					
					sadf = new SADFragmentation();
					sadf.setNoOfChunksPerFrag(noOfChunksPerFrag);					

					streamStartTime = SimClock.getTime() +prebuffer;
				}
				
				//for uninterested watcher, just save
				broadcasterAddress = (DTNHost) msg.getProperty("source");
				lastChokeInterval = SimClock.getTime();
				lastOptimalInterval = SimClock.getTime();
				sendEventToListeners(StreamAppReporter.BROADCAST_RECEIVED, SimClock.getTime(), host);
			}
			
			else if (msg_type.equals(HELLO)){
				int otherStatus = (int) msg.getProperty("status");
				int otherAck = (int) msg.getProperty("ack");
				ArrayList<Integer> otherBuffermap = (ArrayList<Integer>) msg.getProperty("buffermap");
//				System.out.println(host  + " received HELLO " + msg.getFrom() + " : "  + otherBuffermap);
				
				if (stream!=null && otherAck==-1 && otherStatus==-1 && (otherBuffermap==null || otherBuffermap.isEmpty())){ //if watcher does not know someone has a stream
					sendBroadcast(host, msg.getFrom());
					ArrayList<Integer> tempH = copyInt(stream.getBuffermap());	
					sendBuffermap(host, msg.getFrom(), tempH); 
					helloSent.put(msg.getFrom(), tempH);
				}
				
				else if (stream!=null && otherBuffermap!=null && broadcasterAddress!=null){ //if othernode has chunks
					if (!otherBuffermap.isEmpty()){
						
						updateChunksAvailable(msg.getFrom(), otherBuffermap); // save the buffermap received from this neighbor
						
						//if we are unchoked from this node and we can get something from it
						if (availableNeighbors.contains(msg.getFrom()) && isInterested(otherAck,  msg.getFrom())){
//							System.out.println(host + " requesting immediately to: " + msg.getFrom());
							timeToAskNew(host); //evaluate what we can get based on latest updates
						}
						else{
							if (isInterested(otherAck, msg.getFrom())){ //send interested
								sendInterested(host, msg.getFrom(), true);
							}
							else if(availableNeighbors.contains(msg.getFrom())){ //if not interested but it previously unchoked us
								sendInterested(host, msg.getFrom(), false);
								availableNeighbors.remove(msg.getFrom());
							}
						}
					}
				}
				if (!hasHelloed(msg.getFrom())){
					ArrayList<Integer> tempH;
					if (stream!=null)
						tempH = copyInt(stream.getBuffermap());
					else{
						tempH = new ArrayList<Integer>();
					}
//					System.out.println(host + " sending hello: " + tempH);
					sendBuffermap(host, msg.getFrom(), tempH);
					helloSent.put(msg.getFrom(), tempH);
				}
				
			}

			else if(msg_type.equalsIgnoreCase(BROADCAST_CHUNK_SENT)){ //received chunks				
				
				StreamChunk chunk = (StreamChunk) msg.getProperty("chunk");
				int fragId = chunk.getFragmentIndex();
				
				if (stream.getChunk(chunk.getChunkID())==null && sadf.getNoOfChunksPerFrag()>0){
					if (!sadf.doesExist(fragId)){
						sadf.initTransLevelFrag(fragId);
					}
					
					//check if we should include it on fragment now
					if (sadf.doesExist(fragId) && !sadf.getFragment(fragId).isComplete()){						
						sadf.addChunkToFragment(fragId, chunk.getChunkID() % sadf.getNoOfChunksPerFrag(), chunk.getChunkID());
						if (sadf.getFragment(fragId).isComplete()){
							sendEventToListeners(StreamAppReporter.FRAGMENT_CREATED, null, host);
						}
					}
				}
				
//				System.out.println(host  + " RECEIVED CHUNK " +chunk.getChunkID() );
				interpretChunks(host, chunk, msg.getFrom());
			}
			
			else if(msg_type.equalsIgnoreCase(BROADCAST_REQUEST)){
				int chunk = (int) msg.getProperty("chunk");
				
//				if (noOfChunksPerFrag == 0){
					try {
						receivedRequests.get(msg.getFrom()).add(chunk);
					}catch(NullPointerException e){
						receivedRequests.put(msg.getFrom(), new ArrayList<Integer>());
						receivedRequests.get(msg.getFrom()).add(chunk);
					}
//					System.out.println(" Received chunk requests: " + receivedRequests.values());
//					sendWithoutFrag(host, msg)
//				}
//				else{
//					evaluateToSend(host, msg);
//				}
			}
			else if (msg_type.equalsIgnoreCase(BROADCAST_BUNDLED_REQUEST)){
//				System.out.println(" received broadcast bundled");
				ArrayList<Integer> bundledR = (ArrayList<Integer>) msg.getProperty("chunk");
//				System.out.println(host + " received bundled request: " + bundledR);

				try {
					receivedRequests.get(msg.getFrom()).addAll(bundledR);
				}catch(NullPointerException e){
					receivedRequests.put(msg.getFrom(), bundledR);
				}
//				System.out.println(" Bundled received requests: " + receivedRequests.values());
			}
			
			else if (msg_type.equals(INTERESTED)){ 	//evaluate response if choke or unchoke
//				System.out.println(host + " received interested " + msg.getFrom());
				if (!interestedNeighbors.contains(msg.getFrom())){
					interestedNeighbors.add(msg.getFrom());
				}
				evaluateResponse(host, msg.getFrom());
			}

			else if (msg_type.equalsIgnoreCase(UNINTERESTED)){
//				System.out.println(host + " received uninterested " + msg.getFrom());
				interestedNeighbors.remove(msg.getFrom());
				if (unchoked.contains(msg.getFrom())){
					unchoked.set(unchoked.indexOf(msg.getFrom()), null);
				}
			}
			else if (msg_type.equals(UNCHOKE)){
//				System.out.println(host +" received unchoke from " + msg.getFrom());
				
				ArrayList<Integer> temp = (ArrayList<Integer>) msg.getProperty("buffermap");
				updateChunksAvailable(msg.getFrom(), temp);
				availableNeighbors.add(msg.getFrom()); //add this to available neighbors
				
//				System.out.println("@unchoke request to: " + msg.getFrom());
//				if (isInterested(stream.getAck(), neighborData.get(msg.getFrom()))){
//					requestFromNeighbors(host, msg.getFrom());
//				}
				sendEventToListeners(StreamAppReporter.UPDATE_AVAILABLE_NEIGHBORS, availableNeighbors, host);
			}

			else if (msg_type.equals(CHOKE)){
//				System.out.println(host + " received choke from " + msg.getFrom());
				//remove didi an neighbors na dati nag unchoke ha at. diri na hya api ha mga dapat aruan
				availableNeighbors.remove(msg.getFrom());
				sendEventToListeners(StreamAppReporter.UPDATE_AVAILABLE_NEIGHBORS, availableNeighbors, host);
			}
			
			else if(msg_type.equalsIgnoreCase(BROADCAST_FRAGMENT_SENT)){
//				Fragment frag = (Fragment) msg.getProperty("fragment");
//				System.out.println(host + " BROADCAST fragment received");
//				System.out.println(host + " received FRAGMENT " + fragId + " type: " + fragType);
				
				decodeFragment(host, msg, msg.getFrom());
			}
		}
		return msg;
	}
	
	private void decodeFragment(DTNHost host, Message msg, DTNHost from) {
		int fragId = (int) msg.getProperty("frag_id");
		int fragType = (int) msg.getProperty("frag_type");
		ArrayList<StreamChunk> bundled = (ArrayList<StreamChunk>) msg.getProperty("frag_bundled");
		
		if (fragType == SADFragmentation.INDEX_LEVEL){
//			System.out.println(host + " received trans fragment");
			
			ArrayList<Integer> f = new ArrayList<Integer>();
			
			for (StreamChunk c: bundled){
				f.add(c.getChunkID());
				interpretChunks(host, c, from);
			}
			sadf.createFragment(fragId,f);
			sadf.getFragment(fragId).setIndexComplete();
			sendEventToListeners(StreamAppReporter.FRAGMENT_CREATED, null, host);
		}

		else{
//			System.out.println(host + " received trans fragment");
			int startPos = (int) msg.getProperty("start_pos");
			int endPos = (int) msg.getProperty("end_pos");
			
			ArrayList<StreamChunk> bundle = bundled;
			int currFrag = fragId;
			
			if (!sadf.doesExist(currFrag)){
				sadf.initTransLevelFrag(fragId);
			}

			for (int i=0, pos = startPos; pos<=endPos; pos++, i++){
				sadf.addChunkToFragment(currFrag, pos, bundle.get(i).getChunkID());
				interpretChunks(host, bundle.get(i), from);
			}
			
			if (sadf.getFragment(fragId).isComplete()){
				sendEventToListeners(StreamAppReporter.FRAGMENT_CREATED, null, host);
			}
		}
	}

	private void interpretChunks(DTNHost host, StreamChunk chunk, DTNHost from){
		
		if (stream.getBuffermap().size()==0){ //first time received
			sendEventToListeners(StreamAppReporter.FIRST_TIME_RECEIVED, SimClock.getTime(), host);
		}

		if (stream.getChunk(chunk.getChunkID())==null){ //if this is not a duplicate
			stream.addChunk(chunk);			
			stream.setAck(chunk.getChunkID());
			sendEventToListeners(StreamAppReporter.UPDATE_ACK, stream.getAck(), host);
			sendEventToListeners(StreamAppReporter.RECEIVED_CHUNK, chunk.getChunkID(), host);
			updateHello(host, chunk.getChunkID(), from);
		}
		else{
			sendEventToListeners(StreamAppReporter.RECEIVED_DUPLICATE, chunk.getChunkID(), host);
		}
//		System.out.println(host + " received chunk: " + chunk.getChunkID() + " : " + stream.getAck());
		chunkRequest.remove(chunk.getChunkID()); //remove granted requests
//		//receivedFirstPlayable
//		if (stream.getTimeStarted() <= (chunk.getCreationTime() + stream.getDurationPerChunk()) && stream.getAck()==-1){
//			stream.setChunkStart(chunk.getChunkID());
//			status = PLAYING;
//			this.lastTimePlayed=SimClock.getTime();
//			sendEventToListeners(StreamAppReporter.STARTED_PLAYING, lastTimePlayed, host);
//		}
	}		
	
	private void evaluateResponse(DTNHost host, DTNHost to){ //evaluate if we should choke or unchoke this node that sent INTERESTED at time before chokeInterval
		int ctr=0;
		try{
			while(unchoked.get(ctr)!=null && ctr<4){
				ctr++;
			}
		}catch(IndexOutOfBoundsException e){}
		
		if (ctr<4 && !unchoked.contains(to)){
			sendResponse(host, to, true);
			unchoked.set(ctr,to);
			sendEventToListeners(StreamAppReporter.UNCHOKED, unchoked, host);
		}
	}
	
	private void sendRequest(DTNHost host, DTNHost to, int chunkNeeded){
//		System.out.println( host + " send request: " + chunkNeeded);
		
		if (stream.getBuffermap().isEmpty() && chunkRequest.size()==1 && status==WAITING){
			sendEventToListeners(StreamAppReporter.FIRST_TIME_REQUESTED, SimClock.getTime(), host);
		}

		String id = APP_TYPE + ":request_" +SimClock.getFormattedTime(2) + "-" + host.getAddress()+"-"+ to;
		
		Message m = new Message(host, to, id, SIMPLE_MSG_SIZE);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", BROADCAST_REQUEST);
		m.addProperty("chunk", chunkNeeded); //new ArrayList<Long>()
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 3);
		host.createNewMessage(m);
	}
	
	private void sendFRequest(DTNHost host, DTNHost to, ArrayList<Integer> chunkNeeded){
//		System.out.println( host + " sending  frequest: " + chunkNeeded);
		
		if (stream.getBuffermap().isEmpty() && chunkRequest.size()==1 && status==WAITING){
			sendEventToListeners(StreamAppReporter.FIRST_TIME_REQUESTED, SimClock.getTime(), host);
		}

		String id = APP_TYPE + ":request_" +SimClock.getFormattedTime(2) + "-" + host.getAddress()+"-"+ to;
		
		Message m = new Message(host, to, id, SIMPLE_MSG_SIZE);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("surplus", stream.getAck()-stream.getPlaying());
		m.addProperty("msg_type", BROADCAST_BUNDLED_REQUEST);
		m.addProperty("chunk", chunkNeeded); //new ArrayList<Long>()
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 3);
		host.createNewMessage(m);
	}
	
	private void sendInterested(DTNHost host, DTNHost to, boolean isInterested) {
		String id;
		String msgType;
		if (isInterested){
			id = APP_TYPE + ":interested_" + SimClock.getFormattedTime(2) +"-" + host + "-" +to;
			msgType= INTERESTED;
		}
		else{
			id = APP_TYPE + ":uninterested_" + SimClock.getFormattedTime(2) + "-" + host + "-" +to;
			msgType=UNINTERESTED;
		}
		
		Message m = new Message(host, to, id, SIMPLE_MSG_SIZE);
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type",msgType);
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 4);
		host.createNewMessage(m);

		sendEventToListeners(INTERESTED, null, host);
	}
	
	private boolean isInterested(int ack, DTNHost otherHost){
		if (neighborData.get(otherHost) == null) return false;
//		try{
			if (stream.getAck() < ack){ //naive, not yet final
				return true;
			}
			holdChunk.clear();
			holdChunk.addAll(copyInt(neighborData.get(otherHost)));
			holdChunk.removeAll(stream.getBuffermap());
			holdChunk.removeAll(chunkRequest.keySet());
			return !holdChunk.isEmpty();
//		}catch(NullPointerException e){
//			return true;
//		}
	}
	
	@Override
	public void update(DTNHost host) {
		double curTime = SimClock.getTime();
		
		for (DTNHost gone: checkHelloedConnection(host)){
			neighborData.remove(gone);
			availableNeighbors.remove(gone);
//			System.out.println(host + " removed neighbor: " + gone);
		}

		for (DTNHost newHost: getNewConnections()){
			try{
				helloSent.put(newHost, copyInt(stream.getBuffermap()));
				sendBuffermap(host, newHost, copyInt(stream.getBuffermap())); //say hello to new connections
			}catch(NullPointerException e){
				helloSent.put(newHost, new ArrayList<Integer>());
				sendBuffermap(host, newHost, null);
			}
//			System.out.println(host + " sent hello to new neighbor: " + newHost);
		}
		
		removeExpiredRequest();
		timeToAskNew(host);
//		grantRequests(host);
		
		Iterator<Map.Entry<DTNHost, ArrayList<Integer>>> it = receivedRequests.entrySet().iterator();
		while(it.hasNext()){
			DTNHost to = it.next().getKey();
			
			if (sadf.getNoOfChunksPerFrag()>0){
				evaluateToSend(host, to);
			}
			else{
				sendWithoutFrag(host, to);
			}
			it.remove();
		}
		
		if (curTime-lastChokeInterval >= rechokeInterval){
			updateUnchoke(curTime, host);
		}
		
		try{
			if (isWatching && (curTime-this.lastTimePlayed >= stream.getDurationPerChunk()) && curTime>=streamStartTime){
				
				if (prebuffer>0 && stream.isBufferReady(stream.getNext()) && !stream.isReady(stream.getNext())){
					sendEventToListeners(StreamAppReporter.SKIPPED_CHUNK, stream.getNext(), host);
//					System.out.println(host + " skipped chunk: " + props.getNext());
					stream.skipNext();
				}

				if(stream.isReady(stream.getNext())){
					stream.playNext();
					this.lastTimePlayed = curTime;
					sendEventToListeners(StreamAppReporter.LAST_PLAYED, stream.getPlaying(), host);
//					System.out.println(host + " playing: " + stream.getPlaying());

					if (status==WAITING){
						sendEventToListeners(StreamAppReporter.RESUMED, stream.getPlaying(), host);
					}
					status = PLAYING;
				}
				else if (status==PLAYING){
					//hope for the best na aaruon utro ini na missing
					status = WAITING;
					sendEventToListeners(StreamAppReporter.INTERRUPTED, stream.getNext(), host);
//					System.out.println(host+ " waiting: " + props.getNext());
				}
			}
		}catch(NullPointerException e){
		}catch(ArrayIndexOutOfBoundsException i){ }
	
	}
	
	
	private void grantIndivRequests(DTNHost host) {

		for(DTNHost to : receivedRequests.keySet()){
			ArrayList<Integer> msgs = receivedRequests.get(to);
			Iterator<Integer> i = msgs.iterator();
//			System.out.println("Request Msgs: " +msgs);
			while (i.hasNext()){
				sendChunk(stream.getChunk(i.next()), host, to);
				i.remove();
			}
		}
	}
	
	
	public void updateChunksAvailable(DTNHost from, ArrayList<Integer> newBuffermap){
		if (newBuffermap.isEmpty()) return;

//		System.out.println(from + " oldBuffermap: " + neighborData.get(from));
		newBuffermap.removeAll(stream.getBuffermap());
		if (neighborData.containsKey(from)){
			newBuffermap.removeAll(neighborData.get(from));
			neighborData.get(from).addAll(copyInt(newBuffermap));
//			System.out.println(" neighbor existing");
		}
		else{
			neighborData.put(from, copyInt(newBuffermap));
//			System.out.println(" new neighbor");
		}
//		System.out.println(from + " newBuffermap: " + neighborData.get(from));
	}

	private void sendWithoutFrag(DTNHost host, DTNHost to){
		ArrayList<Integer> request = receivedRequests.get(to);
		
		for (int id : request){
			sendChunk(stream.getChunk(id), host, to);
		}
		
//		int request = (int) msg.getProperty("chunk");
//		sendChunk(stream.getChunk(request), host, msg.getFrom());
	}
	
	private void sendChunk(StreamChunk chunk, DTNHost host, DTNHost to) {
//		System.out.println(host + " sending chunk " + chunk.getChunkID());
		
		String id = APP_TYPE + ":chunk_" + chunk.getChunkID() +  " " + chunk.getCreationTime() +"-" +to + "-" + host.getAddress();
		
		Message m = new Message(host, to, id, (int) stream.getByterate());		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", BROADCAST_CHUNK_SENT);
		m.addProperty("chunk", chunk);	
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 2);
		host.createNewMessage(m);

		sendEventToListeners(StreamAppReporter.SENT_CHUNK, chunk, host);
	}

	private void timeToAskNew(DTNHost host){
		if (availableNeighbors.isEmpty()) return;
		Collections.sort(availableNeighbors, StreamingApplication.BandwidthComparator);
	
		int surplus = stream.getAck()-stream.getPlaying();
		int maxRequestPerNode = surplus/availableNeighbors.size();
		if (maxRequestPerNode==0) maxRequestPerNode=1;
		
		
		Iterator<DTNHost> iterator = availableNeighbors.iterator();
			
		while (iterator.hasNext()){
			DTNHost to = iterator.next();
			
			if (chunkRequest.size() > maxPendingRequest) break;

			if (!neighborData.containsKey(to)){ //remove on availableNeighbors
				iterator.remove();
				continue;
			}
			
			holdChunk.clear();
			holdChunk.addAll(copyInt(neighborData.get(to)));
			holdChunk.removeAll(stream.getBuffermap());
			holdChunk.removeAll(chunkRequest.keySet());
			Collections.sort(holdChunk);
		
			if (maxRequestPerNode>1 && sadf.getNoOfChunksPerFrag()>0){
				requestBundle(host, to, maxRequestPerNode);
			}
			else{
				requestChunk(host, to);
			}
		}
	}

//	/*
//	 * called everytime availableNeighbors is updated
//	 */
//	private void requestFromNeighbors(DTNHost host, DTNHost otherNode){
////		System.out.println(host + " requesting to neighbor " + otherNode);
//		int maxRequestPerNode = maxPendingRequest/availableNeighbors.size(); //be sure what really happens with this.
//		
//		holdChunk.clear();
//		holdChunk.addAll(copyInt(neighborData.get(otherNode)));
//		holdChunk.removeAll(stream.getBuffermap());
//		holdChunk.removeAll(chunkRequest.keySet());
//		Collections.sort(holdChunk);
//		
//		int windowStart = stream.getAck()+1;
//		int windowEnd = (stream.getWindowSize() + windowStart)-1;
//		
//		double expiry = -1;
//		for (int chunkId : holdChunk){
//			if (chunkId>windowEnd) break;
//			
//			if (chunkId >=windowStart && chunkId<=windowEnd){
//				expiry = (((chunkId*stream.getDurationPerChunk()) - (stream.getPlaying()*stream.getDurationPerChunk()))+
//							SimClock.getTime()) - waitingThreshold;
//				if (expiry <= SimClock.getTime() ){
//					expiry = SimClock.getTime() + waitingThreshold;
//				}
//				
//				chunkRequest.put(chunkId, expiry);
//				sendRequest(host, otherNode, chunkId);
//				sendEventToListeners(StreamAppReporter.SENT_REQUEST, chunkId,host);
//				break;
//			}
//		}
//	}
	
	public void requestBundle(DTNHost host, DTNHost to, int maxRequestPerNode){
		ArrayList<Integer> toRequest = new ArrayList<Integer>();
		
		double expiry = -1;
		Iterator<Integer> iter = holdChunk.iterator();
		
		for (int j=0; iter.hasNext() && toRequest.size() < maxRequestPerNode && j<holdChunk.size() 
				&& chunkRequest.size()<maxPendingRequest; j++){
			
			int chunkId = iter.next();

			expiry = (((chunkId*stream.getDurationPerChunk()) - (stream.getPlaying()*stream.getDurationPerChunk()))+
						SimClock.getTime()) - waitingThreshold;
			if (expiry <= SimClock.getTime() ){
				expiry = SimClock.getTime() + waitingThreshold;
			}
			
			chunkRequest.put(chunkId, expiry);
			toRequest.add(chunkId);
			iter.remove();
		}

		if (!toRequest.isEmpty()){
			sendFRequest(host, to, toRequest);
//			System.out.println(" sent bundle request " + toRequest);
			sendEventToListeners(StreamAppReporter.SENT_BUNDLE_REQUEST, toRequest, host);
		}
	}
	
	public void requestChunk(DTNHost host, DTNHost otherNode){
	
		int windowStart = stream.getAck()+1;
		int windowEnd = (stream.getWindowSize() + windowStart)-1;
		
		double expiry = -1;
		for (int chunkId : holdChunk){
			if (chunkId>windowEnd || chunkRequest.size()>maxPendingRequest) break;
			
			if (chunkId >=windowStart && chunkId<=windowEnd){
				expiry = (((chunkId*stream.getDurationPerChunk()) - (stream.getPlaying()*stream.getDurationPerChunk()))+
							SimClock.getTime()) - waitingThreshold;
				if (expiry <= SimClock.getTime() ){
					expiry = SimClock.getTime() + waitingThreshold;
				}
				
				chunkRequest.put(chunkId, expiry);
//				System.out.println(" sent chunk request " + chunkId);
				sendRequest(host, otherNode, chunkId);
				sendEventToListeners(StreamAppReporter.SENT_REQUEST, chunkId,host);
				break;
			}
		}
	}
	
	public void updateHello(DTNHost host, int newChunk, DTNHost from){
		for (DTNHost gone: checkHelloedConnection(host)){
			neighborData.remove(gone);
			availableNeighbors.remove(gone);
		}
		
		holdChunk.clear(); //temp hold for latest chunk created
		holdChunk.add(newChunk);

		for (DTNHost h: unchoked){
			if (h!=null && !h.equals(from)) {
				sendBuffermap(host, h, copyInt(holdChunk));
//				System.out.println(" helloSent: " + helloSent.get(h));
				try{
					helloSent.get(h).add(newChunk);
				}catch(NullPointerException e){
					helloSent.remove(h);
					updateUnchoked(unchoked.indexOf(h), null);
				}
			}
		}
		
		for (DTNHost h: helloSent.keySet()){
			if (!h.equals(broadcasterAddress) && !unchoked.contains(h) && !h.equals(from)){
				sendBuffermap(host, h, copyInt(holdChunk));
				helloSent.get(h).add(newChunk);
			}
		}
	}
	

	private void evaluateToSend(DTNHost host, DTNHost to) {
		ArrayList<Integer> request = receivedRequests.get(to);
		holdChunk.clear(); //temporary storage for chunks to send
		Collections.sort(request);
		
		int rChunk;
		int currFrag;
		
//		System.out.println(" @@@@@request size: " + request.size());
		while(!request.isEmpty()){
			rChunk = request.get(0);
			currFrag = stream.getChunk(rChunk).getFragmentIndex();
			holdChunk.clear();
			
			if ( sadf.doesExist(currFrag) && sadf.getFragment(currFrag).isComplete() ){ //send fragments
				int fChunk = sadf.getFragment(currFrag).getFirstChunkID();
				int lChunk = sadf.getFragment(currFrag).getEndChunk();

				//if request contains full fragment
				if (request.contains(fChunk) && request.contains(lChunk) && 
					request.subList(request.indexOf(fChunk), request.indexOf(lChunk)+1).size() == sadf.getFragment(currFrag).getNoOfChunks()){

//					System.out.println(" index level request.");
					if (isGreaterThanTrans(host, to, ((sadf.getFragment(currFrag).getNoOfChunks()*stream.getByterate())+HEADER_SIZE))){ //fragment with respect to trans size
						subFragment(host, to, request, currFrag);
					}
					else{
						sendIndexFragment(host, to, sadf.getFragment(currFrag).getBundled(), currFrag);
						request.removeAll(sadf.getFragment(currFrag).getBundled());
						continue;
					}
				}
				
				else{ //if request is not a full fragment
					subFragment(host, to, request, currFrag);
				}
				
				if (holdChunk.size()>1){ //
					int start = sadf.getFragment(currFrag).indexOf(holdChunk.get(0));
					int end = sadf.getFragment(currFrag).indexOf(holdChunk.get(holdChunk.size()-1));
					sendTransFragment(host, to, holdChunk, currFrag, start, end);
					
				}
				else if (holdChunk.size()==1){ //limit trans level == 2 chunks fragmented
//					System.out.println(" send as chunk on trans fragment");
					sendChunk(stream.getChunk(holdChunk.get(0)), host, to);
				}
				else{
//					System.out.println(" sending as chunk only w/o fragment");
					sendChunk(stream.getChunk(rChunk), host, to);
				}
			}
			
			else{ //if fragment does not exist, send chunk individually
				sendChunk(stream.getChunk(rChunk), host, to);
				request.remove(request.indexOf(rChunk));
//				System.out.println(" sending as chunk only no fragment");
			}
		}
	}
	
	/*
	 * called if total chunks to send is greater than translevel
	 */
	private void subFragment(DTNHost host, DTNHost to, ArrayList<Integer> bundle, int currFrag){
//		System.out.println(host + " @subfragment: " + bundle);
		double transSize = 	getTransSize(host, to);
		double currSize;
		int prevChunk;
		
		Iterator<Integer> iter = bundle.iterator();
		prevChunk = bundle.get(0);
		int currChunk;
		
		while(iter.hasNext()){
			currChunk = iter.next();
			currSize = ((holdChunk.size()+1)*stream.getByterate()) + HEADER_SIZE;

			if(currFrag == stream.getChunk(currChunk).getFragmentIndex() && (holdChunk.isEmpty() || currChunk==prevChunk+1) 
					&& currSize < transSize){
				holdChunk.add(currChunk);
				prevChunk = currChunk;
				iter.remove();
			}
			else{
				if (currSize > transSize){
					sendEventToListeners(StreamAppReporter.SIZE_ADJUSTED, null, host);
				}
				break;
			}
		}
	}
	
	private void sendIndexFragment(DTNHost host, DTNHost to, ArrayList<Integer> bundled, int fragId) {
//		System.out.println(host + "  @ send index fragment");
		
		ArrayList<StreamChunk> chunks = new ArrayList<StreamChunk>();
		for (int c : bundled){
			chunks.add(stream.getChunk(c));
		}
		
		String id = APP_TYPE + ":fragment_" + SimClock.getFormattedTime(2) + "-"+  fragId + "-" + to + "-" + host.getAddress();
		double size = (bundled.size()*stream.getByterate()) + HEADER_SIZE;
		
		Message m = new Message(host, to, id,  (int) size);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", BROADCAST_FRAGMENT_SENT);
		m.addProperty("frag_type", SADFragmentation.INDEX_LEVEL);
		m.addProperty("frag_id", fragId);
		m.addProperty("frag_bundled", chunks); //send chunk here
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 2);
		host.createNewMessage(m);
		
		sendEventToListeners(StreamAppReporter.SENT_INDEX_FRAGMENT, null, host);
	}
	
	private void sendTransFragment(DTNHost host, DTNHost to,  ArrayList<Integer> bundled, int fragId,
			int startPos, int endPos) {
		
		ArrayList<StreamChunk> chunks = new ArrayList<StreamChunk>();
		for (int c : bundled){
			chunks.add(stream.getChunk(c));
		}
		
//		System.out.println(host + " sent trans fragment " + to);
		String id = APP_TYPE + ":fragment_"  + SimClock.getFormattedTime(2) + "-" + fragId + "-" + to + "-" + host.getAddress();

		double size = (bundled.size()*stream.getByterate()) + HEADER_SIZE;
		
		Message m = new Message(host, to, id, (int) size);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", BROADCAST_FRAGMENT_SENT);
		m.addProperty("frag_type", SADFragmentation.TRANS_LEVEL);
		m.addProperty("frag_id", fragId);
		m.addProperty("frag_bundled", chunks);
		m.addProperty("start_pos", startPos);
		m.addProperty("end_pos", endPos);
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 2);
		host.createNewMessage(m);
		
		sendEventToListeners(StreamAppReporter.SENT_TRANS_FRAGMENT, null, host);
	}
	
	private boolean isGreaterThanTrans(DTNHost host, DTNHost to, double size){	
		double transSize = ((TVProphetRouterV2) host.getRouter()).getTransmissionPreds(to);
		return size>transSize;
	}
	
	public double getTransSize(DTNHost host, DTNHost to){
		return 	((TVProphetRouterV2) host.getRouter()).getTransmissionPreds(to);
	}
	
	public void sendBroadcast(DTNHost host, DTNHost to){
		String id = APP_TYPE + ":broadcast" + SimClock.getFormattedTime(2) + "-" + host.getAddress() + "-" + to;
		
		Message m= new Message(host, to, id, SIMPLE_MSG_SIZE);
		m.addProperty("type", APP_TYPE);
		m.addProperty("msg_type", BROADCAST_LIVE);
		m.addProperty("streamID", getStreamID());
		m.addProperty("stream_name", "streamName");
		m.addProperty(BYTERATE, stream.getByterate());
		m.addProperty(DURATION_PER_CHUNK, stream.getDurationPerChunk());
		m.addProperty(CHUNKS_PER_FRAG, sadf.getNoOfChunksPerFrag());
		m.addProperty(WINDOW_SIZE, stream.getWindowSize());
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 1);
		m.addProperty("time_started", stream.getTimeStarted());
		m.addProperty("source", broadcasterAddress);
		m.setAppID(APP_ID);
		host.createNewMessage(m); //a broadcast is initiated from this peer
		
		lastChokeInterval = SimClock.getTime();
		lastOptimalInterval = SimClock.getTime();
		super.sendEventToListeners(BROADCAST_LIVE, null, host);
	}
	
	public void updateUnchoke(double curTime, DTNHost host){ //for maintaining -- choking and unchoking
//		System.out.println(" interestedNeighbors: "+ interestedNeighbors);
		if (hasNewInterested()){
			holdHost.clear();
			holdHost.addAll(copyHost(interestedNeighbors)); //hold of prevunchoked list
			
			if (curTime-lastOptimalInterval >= optimisticUnchokeInterval){ //optimistic interval = every 15 seconds

				prevUnchoked.clear();
				prevUnchoked.addAll(copyHost(unchoked));
				
				for (Iterator<DTNHost> i = unchoked.iterator(); i.hasNext(); ){ //remove null values, unchoked may have null values
					DTNHost h = i.next();
					if(!holdHost.contains(h) && h!=null){ //add unique values from unchoked
						holdHost.add(h);
					}
				}
				
				sortNeighborsByBandwidth(holdHost); // ensure this is sorted
				unchokeTop3(host, holdHost, prevUnchoked);
				unchokeRand(host, holdHost, prevUnchoked); //api ha pag random yana an naapi ha unchoke kanina tapos diri na api yana ha newly unchoked
				chokeOthers(host, prevUnchoked); //send CHOKE to mga api ha previous unchoked kanina na diri na api yana
				lastOptimalInterval = curTime;
			}
			
			//choke interval == 5 seconds for random
			else{
				holdHost.removeAll(unchoked.subList(0, 3));
				unchokeRand(host, holdHost, unchoked); //an mga bag-o an pilii random
			}
			sendEventToListeners(StreamAppReporter.UNCHOKED, unchoked, host);
		}
		lastChokeInterval = curTime;
//		System.out.println(host + " unchoked: " + unchoked);
	}
	
	/*
	 * count if an sulod han interested is same la ha mga unchoked
	 */
	private boolean hasNewInterested(){
		if (interestedNeighbors.isEmpty()) return false;

		ArrayList<DTNHost> interested = copyHost(interestedNeighbors); //storage for interested host that are not yet unchoke
		interested.removeAll(unchoked);
		return !interested.isEmpty();
	}
	
	private void sendBuffermap(DTNHost host, DTNHost to, ArrayList<Integer> buffermap){  // ArrayList<Fragment> fragments){
//		System.out.println(host + " sent hello to "+to + " buffermap: " + buffermap);
		String id = APP_TYPE+ ":hello_" + SimClock.getFormattedTime(2) +"-" +host.getAddress() +"-" + to;
		
		Message m = new Message(host, to, id, BUFFERMAP_SIZE); //buffermap size must be defined.
		m.setAppID(APP_ID);
		m.addProperty("type", APP_TYPE);
		m.addProperty("msg_type", HELLO);
		m.addProperty("status", status);
		m.addProperty("buffermap", buffermap);
		
		if (stream!=null){
			m.addProperty("ack", stream.getAck());
		}else{
			m.addProperty("ack", -1);
		}

		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 5);
		host.createNewMessage(m);
	}
	
	private void removeExpiredRequest(){
		double curTime = SimClock.getTime();
		Iterator<Integer> i = chunkRequest.keySet().iterator();
		while(i.hasNext()){
			int chunkID = i.next();
			if (chunkRequest.get(chunkID)<= curTime){ //if the request we sent is expired
				i.remove();
			}
		}
	}
	
	/*
	 * called every 15 seconds.
	 * after this method, unchoked list is updated. recognized includes those na diri na api ha top3
	 */
	private void unchokeTop3(DTNHost host, ArrayList<DTNHost> interested, ArrayList<DTNHost> prevUnchoked){
		if (interested.isEmpty()) return;

		Iterator<DTNHost> i = interested.iterator();
		
 		for (int ctr=0; ctr<3; ctr++){ //send UNCHOKE to top 3
			DTNHost other=null;
			try{
				other = i.next();	
				if (!prevUnchoked.contains(other)){ //if diri hya api ha kanina na group of unchoked
					sendResponse(host, other, true); //send UNCHOKE
				}
				i.remove(); //notification granted, remove (for those at interested
			}catch(NoSuchElementException e){}
			updateUnchoked(ctr, other);
		}
	}

	/*
	 * called every 5 seconds. Get a random node to be unchoked that is not included in the top3
	 * @param recognized interestedNeighbors that are not included in the top 3
	 * 
	 */
	private void unchokeRand(DTNHost host, ArrayList<DTNHost> interested, ArrayList<DTNHost> prevUnchoked){
		if (interested.isEmpty()) return;

		DTNHost prevRand;
		prevRand = prevUnchoked.get(3);
		
		int index = r.nextInt(interested.size()); //possible ini maging same han last random
		DTNHost randNode = interested.get(index);

		if (prevRand!=randNode){
			if (prevRand!=null){
				sendResponse(host, prevRand, false); //send CHOKE to this random node if it is not the same with new node
			}
			if (!prevUnchoked.contains(randNode)){
				sendResponse(host, randNode, true); //sendUnchoke to this random node if it isn't unchoked before
			}
		}
		interested.remove(randNode);
		updateUnchoked(3, randNode);
	}
	
	private void chokeOthers(DTNHost host, ArrayList<DTNHost> interested){ 	//sendChoke to tanan na nabilin
		prevUnchoked.removeAll(unchoked);
		for (DTNHost r : interested){
			if (r!=null){
				sendResponse(host, r, false); 
			}
		}
	}
	
	/*
	 * 
	 * @param isOkay true if response is unchoke. false if choke
	 * 
	 */
	public void sendResponse(DTNHost host, DTNHost to, boolean isOkay){
		String id;
		String msgType; 

		if (isOkay){
			id = APP_TYPE + ":UNCHOKE_" + SimClock.getFormattedTime(2) + "-" + host.getAddress()  +"-" + to;
			msgType = UNCHOKE;
		}
		else{
			id = APP_TYPE + ":CHOKE_" + SimClock.getFormattedTime(2)+ "-" + host.getAddress()  + "-" + to;
			msgType = CHOKE;
		}

		ArrayList<Integer> tempChunk = copyInt(stream.getBuffermap());
		if (helloSent.get(to)!=null) tempChunk.removeAll(helloSent.get(to));
		helloSent.get(to).addAll(tempChunk);

		Message m = new Message(host, to, id, SIMPLE_MSG_SIZE);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", msgType);
		m.addProperty("buffermap", tempChunk);
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 4);
		host.createNewMessage(m);
	}
	
	@Override
	public Application replicate() {
		return new WatcherApp(this);
	}
	
	public int getWatcherType(){
		return watcherType;
	}
	
	private int getPrebuffer() {
		return prebuffer;
	}

	private int getWaitingThreshold() {
		return waitingThreshold;
	}

}

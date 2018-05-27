package applications;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

import core.Application;
import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import fragmentation.Fragment;
import fragmentation.SADFragmentation;
import report.StreamAppReporter;
import routing.TVProphetRouterV2;
import streaming.StreamChunk;
import streaming.Stream;

public class WatcherAppV3 extends StreamingApplication{

	public static final String WATCHER_TYPE = "watcherType"; 
	public static final String WAITING_THRESHOLD = "waitingThreshold";
	public static final String PREBUFFER = "prebuffer";
	
	public static final int PLAYING = 1;
	public static final int WAITING = 0;

	private int 	status =-1;
	private int 	watcherType; //1 if listener. 0 if just a hop
	private static int maxRequestPerNode;
	private static int maxPendingRequest;
	private static int waitingThreshold;
	private static int prebuffer;
	private double lastTimePlayed=0;
	private double lastChokeInterval = 0;
	private double lastOptimalInterval=0;
	private double streamStartTime;
	private boolean isWatching=false;

	private HashMap<Integer, Double> chunkRequest;
	private HashMap<Integer, ArrayList<Integer>> toFragment;
	private SADFragmentation sadf;
	private static DTNHost broadcasterAddress;
	
	private ArrayList<Integer> tempInteger;
	private ArrayList<StreamChunk> tempChunk;
	private ArrayList<DTNHost> tempHost;
	private ArrayList<DTNHost> recognized;
	private HashMap<DTNHost, ArrayList<Integer>> neighborData;
	private ArrayList<DTNHost> availableNeighbors; //neighbors that we can request from
	
	private Random r;
	private Stream stream;
	
	public WatcherAppV3(Settings s) {
		super(s);
		
		watcherType = s.getInt(WATCHER_TYPE);
		waitingThreshold = s.getInt(WAITING_THRESHOLD);
		prebuffer = s.getInt(PREBUFFER);
		
		toFragment = new HashMap<Integer, ArrayList<Integer>>();
		chunkRequest = new HashMap<Integer, Double>();
		tempChunk  = new ArrayList<StreamChunk>();
		tempInteger = new ArrayList<Integer>();
		tempHost = new ArrayList<DTNHost>();
		recognized = new ArrayList<DTNHost>();
		neighborData = new HashMap<DTNHost, ArrayList<Integer>>();
		availableNeighbors = new ArrayList<DTNHost>();
		
		r = new Random();
		initUnchoke();
	}

	public WatcherAppV3(WatcherAppV3 a) {
		super(a);
		
		watcherType = a.getWatcherType();
		waitingThreshold = a.getWaitingThreshold();
		prebuffer = a.getPrebuffer();

		toFragment = new HashMap<Integer, ArrayList<Integer>>();
		chunkRequest = new HashMap<Integer, Double>();
		tempChunk  = new ArrayList<StreamChunk>();
		tempInteger = new ArrayList<Integer>();
		tempHost = new ArrayList<DTNHost>();
		recognized = new ArrayList<DTNHost>();
		neighborData = new HashMap<DTNHost, ArrayList<Integer>>();
		availableNeighbors = new ArrayList<DTNHost>();
		
		r = new Random();
		initUnchoke();
	}

	private int getPrebuffer() {
		return prebuffer;
	}

	private int getWaitingThreshold() {
		return waitingThreshold;
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
					
					String streamID=(String) msg.getProperty("streamID");
					double timeStarted = (double) msg.getProperty("time_started");
					double byterate = (double) msg.getProperty(BYTERATE);
					int durationPerChunk = (int) msg.getProperty(DURATION_PER_CHUNK);
					int noOfChunksPerFrag = (int) msg.getProperty(CHUNKS_PER_FRAG);
					
//					this.stream = new Stream(streamID, durationPerChunk, byterate, timeStarted);
					
					sadf = new SADFragmentation();
					sadf.setNoOfChunksPerFrag(noOfChunksPerFrag);					

					maxPendingRequest = (noOfChunksPerFrag*2)+50;
					streamStartTime = SimClock.getTime() +prebuffer;
				}
				
				///for uninterested watcher, just save
				broadcasterAddress = (DTNHost) msg.getProperty("source");
				lastChokeInterval = SimClock.getTime();
				lastOptimalInterval = SimClock.getTime();
				sendEventToListeners(StreamAppReporter.BROADCAST_RECEIVED, SimClock.getTime(), host);
			}
			
			else if (msg_type.equals(HELLO)){
				
				int otherStatus = (int) msg.getProperty("status");
				int otherAck = (int) msg.getProperty("ack");
				ArrayList<Integer> otherBuffermap = (ArrayList<Integer>) msg.getProperty("buffermap");
				System.out.println(host  + " received HELLO " + msg.getFrom() + " : "  + otherBuffermap);
				
				if (stream!=null && otherAck==-1 && otherStatus==-1
						&& otherBuffermap.isEmpty() ){ //if watcher does not know someone has a stream
					sendBroadcast(host, msg.getFrom());
					sendBuffermap(host, msg.getFrom(), stream.getBuffermap()); 
					helloSent.put(msg.getFrom(), stream.getBuffermap());
				}
				
				else if (!otherBuffermap.isEmpty() && broadcasterAddress!=null){ //if othernode has chunks
					updateChunksAvailable(msg.getFrom(), otherBuffermap); // save the buffermap received from this neighbor
				
					//if we are unchoked from this node and we can get something from it
					if (availableNeighbors.contains(msg.getFrom()) && isInterested(otherAck,  otherBuffermap)){
						requestFromNeighbors(host, msg.getFrom()); //evaluate what we can get based on latest updates
					}
					else{
//						ArrayList<Long> temp = (ArrayList<Long>) .clone();
						if (isInterested(otherAck,  neighborData.get(msg.getFrom()))){ //send interested
							sendInterested(host, msg.getFrom(), true);
						}
						else if(availableNeighbors.contains(msg.getFrom())){ //if not interested but it previously unchoked us
							sendInterested(host, msg.getFrom(), false);
							availableNeighbors.remove(msg.getFrom());
						}
					}
				}
				else{
					if (!hasHelloed(msg.getFrom())){
						if (stream!=null){
							sendBuffermap(host, msg.getFrom(), new ArrayList<Integer>(stream.getBuffermap())); 
							helloSent.put(msg.getFrom(), stream.getBuffermap());
						}
					}
				}
			}
			
			else if(msg_type.equalsIgnoreCase(BROADCAST_CHUNK_SENT)){ //received chunks				
				
				StreamChunk chunk = (StreamChunk) msg.getProperty("chunk");
				int fragId = chunk.getFragmentIndex();
				
				System.out.println(host  + " RECEIVED CHUNK " +chunk.getChunkID() );
				
				//for fragmenting chunks that are individually received
				if (stream.getChunk(chunk.getChunkID())==null && sadf.getNoOfChunksPerFrag()>0){
						//save on toFragment
						if (toFragment.containsKey(fragId)){
							toFragment.get(chunk.getFragmentIndex()).add(chunk.getChunkID());
						}
						else{
							tempInteger.clear();
							tempInteger.add(chunk.getChunkID());
//							toFragment.put(fragId, (ArrayList<StreamChunk>) tempChunk.clone());
						}
						
						if (!sadf.doesExist(fragId)){
							sadf.initTransLevelFrag(fragId);
						}
						
						//check if we should include it on fragment now
						if (sadf.doesExist(fragId) && !sadf.getFragment(fragId).isComplete()){
							tempInteger.clear();
							tempInteger.addAll(toFragment.get(fragId));
							
							for (StreamChunk c: tempChunk){
								sadf.addChunkToFragment(fragId, ((int)c.getChunkID())%sadf.getNoOfChunksPerFrag(), c.getChunkID());
							}
							if (sadf.getFragment(fragId).isComplete()){
								sendEventToListeners(StreamAppReporter.FRAGMENT_CREATED, null, host);
							}
							toFragment.remove(fragId);
					}
				}
				interpretChunks(host, chunk, msg.getFrom());
			}
			
			else if(msg_type.equalsIgnoreCase(BROADCAST_FRAGMENT_SENT)){
				Fragment frag = (Fragment) msg.getProperty("fragment");
				int fragType = (int) msg.getProperty("frag_type");
				System.out.println(host + " received FRAGMENT " + frag.getId() + " type: " + fragType);
				
				decodeFragment(host, frag, fragType, msg.getFrom());
			}
			
			else if(msg_type.equalsIgnoreCase(BROADCAST_REQUEST)){
				System.out.println(host + " received REQUEST from " + msg.getFrom());
			
				if (sadf.getNoOfChunksPerFrag() == 0 ){
					sendWithoutFrag(host, msg);
				}
				else{
					evaluateToSend(host, msg);
				}
			}

			else if (msg_type.equals(INTERESTED)){ 	//evaluate response if choke or unchoke
				System.out.println(host + " received interested " + msg.getFrom());
				interestedNeighbors.add(msg.getFrom());
				evaluateResponse(host, msg.getFrom());
			}

			else if (msg_type.equalsIgnoreCase(UNINTERESTED)){
				System.out.println(host + " received uninterested " + msg.getFrom());
				interestedNeighbors.remove(msg.getFrom());
				if (unchoked.contains(msg.getFrom())){
					unchoked.set(unchoked.indexOf(msg.getFrom()), null);
				}
			}
			
			else if (msg_type.equals(UNCHOKE)){
				System.out.println(host +" received unchoke from " + msg.getFrom());
				
				ArrayList<Integer> temp = (ArrayList<Integer>) msg.getProperty("buffermap");
				updateChunksAvailable(msg.getFrom(), temp);
				availableNeighbors.add(msg.getFrom()); //add this to available neighbors
				
				requestFromNeighbors(host, msg.getFrom());
				sendEventToListeners(StreamAppReporter.UPDATE_AVAILABLE_NEIGHBORS, availableNeighbors, host);
			}

			else if (msg_type.equals(CHOKE)){
				System.out.println(host + " received choke from " + msg.getFrom());
				//remove didi an neighbors na dati nag unchoke ha at. diri na hya api ha mga dapat aruan
				availableNeighbors.remove(msg.getFrom());
				sendEventToListeners(StreamAppReporter.UPDATE_AVAILABLE_NEIGHBORS, availableNeighbors, host);
			}

		}
		return msg;
	}

	private void decodeFragment(DTNHost host, Fragment frag, int fragType, DTNHost from) {

		if (fragType == SADFragmentation.INDEX_LEVEL){
			
			sadf.createFragment(frag.getId(), frag.getBundled());
			sendEventToListeners(StreamAppReporter.FRAGMENT_CREATED, null, host);
			sadf.getFragment(frag.getId()).setIndexComplete();
			
			for (int c: frag.getBundled()){
//				interpretChunks(host, , from);
			}
		}

		else{
			ArrayList<Integer> bundle = frag.getBundled();
			int currFrag = frag.getId();
			
			if (!sadf.doesExist(currFrag)){
				sadf.initTransLevelFrag(frag.getId());
	
			}

			for (int i=0, pos = frag.getStartPosition(); pos<=frag.getEndPosition(); pos++, i++){
				sadf.addChunkToFragment(currFrag, pos, bundle.get(i));
//				interpretChunks(host, bundle.get(i), from);
			}
			
			if (sadf.getFragment(frag.getId()).isComplete()){
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

		chunkRequest.remove(chunk.getChunkID()); //remove granted requests
		if (chunkRequest.size() <= maxPendingRequest/2 && !availableNeighbors.isEmpty()){
			timeToAskNew(host);
		}
		
		//receivedFirstPlayable
		if (stream.getTimeStarted() <= (chunk.getCreationTime() + stream.getDurationPerChunk()) && stream.getAck()==0){
//			stream.setChunkStart(chunk.getChunkID());
			status = PLAYING;
			this.lastTimePlayed=SimClock.getTime();
			sendEventToListeners(StreamAppReporter.STARTED_PLAYING, lastTimePlayed, host);
		}
	}
	
	@Override
	public void update(DTNHost host) {
		double curTime = SimClock.getTime();
		
		for (DTNHost temp: checkHelloedConnection(host)){
			neighborData.remove(temp);
			availableNeighbors.remove(temp);
		}
		
		removeExpiredRequest();
		
		try{
			List<Connection> hostConnection = host.getConnections(); 
			hostConnection.removeAll(helloSent.keySet()); //get hosts we haven't said hello to yet
			for (Connection c: hostConnection){
				DTNHost otherNode = c.getOtherNode(host);
				if (c.isUp() && !hasHelloed(otherNode)){
					tempInteger.clear();
					if (stream!=null){
						tempInteger.addAll(stream.getBuffermap());
					}
					helloSent.put(otherNode, tempInteger);
					sendBuffermap(host, otherNode, tempInteger); //say hello to new connections
				}
			}
		}catch(NullPointerException e){
			e.printStackTrace();
		}
		
		updateUnchoke(curTime, host);
		
		try{
			if (isWatching && (curTime-this.lastTimePlayed >= stream.getDurationPerChunk()) && curTime>=streamStartTime){
				
				if (prebuffer>0 && stream.isBufferReady(stream.getNext()) && !stream.isReady(stream.getNext())){
					sendEventToListeners(StreamAppReporter.SKIPPED_CHUNK, stream.getNext(), host);
//					System.out.println(host + " skipped chunk: " + props.getNext());
					stream.skipNext();
				}

				if(stream.isReady(stream.getNext())){
					stream.playNext();
					status = PLAYING;
					this.lastTimePlayed = curTime;
					sendEventToListeners(StreamAppReporter.LAST_PLAYED, lastTimePlayed, host);
//					System.out.println(host + " playing: " + props.getPlaying());
				}
				else {
					//hope for the best na aaruon utro ini na missing
					status = WAITING;
					sendEventToListeners(StreamAppReporter.INTERRUPTED, null, host);
//					System.out.println(host+ " waiting: " + props.getNext());
				}
			}
		}catch(NullPointerException e){
		}catch(ArrayIndexOutOfBoundsException i){ }
		
		
	}
	
	public void updateUnchoke(double curTime, DTNHost host){ //for maintaining -- choking and unchoking
		
		if (curTime-lastChokeInterval >= rechokeInterval){
			
			if (hasNewInterested()){
				recognized.clear();
				recognized.addAll(interestedNeighbors);
				
				tempHost.clear(); //hold of prevunchoked list
				tempHost.addAll(unchoked);
				
				if (curTime-lastOptimalInterval >= optimisticUnchokeInterval){ //optimistic interval = every 15 seconds
					
					for (Iterator<DTNHost> i = unchoked.iterator(); i.hasNext(); ){ //remove null values, unchoked may have null values
						DTNHost h = i.next();
						if(!recognized.contains(h) && h!=null){ //add unique values from unchoked
							recognized.add(h);
						}
					}
					
					sortNeighborsByBandwidth(recognized);
//					System.out.println(host + " interested nodes: " +recognized);
					unchokeTop3(host, recognized);

					unchokeRand(host, recognized, tempHost); //api ha pag random yana an naapi ha unchoke kanina tapos diri na api yana ha newly unchoked
					tempHost.removeAll(unchoked); //remove an tanan na previous na api na ha newly unchoked
					chokeOthers(host, tempHost); //send CHOKE to mga api ha previous unchoked kanina na diri na api yana
					lastOptimalInterval = curTime;
				}
				
				//choke interval == 5 seconds for random
				else{
					recognized.add(unchoked.get(3));
					unchokeRand(host, recognized, tempHost); //an mga bag-o an pilii random
				}
				
				sendEventToListeners(StreamAppReporter.UNCHOKED, unchoked, host);
				sendEventToListeners(StreamAppReporter.INTERESTED, recognized, host);
			}
			lastChokeInterval = curTime;
//			System.out.println(host + " Unchoked: " +unchoked);
		}
		
		
	}
	
	public void sendBroadcast(DTNHost host, DTNHost to){
		String id = APP_TYPE + ":broadcast" + SimClock.getIntTime() + "-" + host.getAddress() + "-" + to;
		
		Message m= new Message(host, to, id, SIMPLE_MSG_SIZE);
		m.addProperty("type", APP_TYPE);
		m.addProperty("msg_type", BROADCAST_LIVE);
		m.addProperty("streamID", getStreamID());
		m.addProperty("stream_name", "tempstream");
		m.addProperty(BYTERATE, stream.getByterate());
		m.addProperty(DURATION_PER_CHUNK, stream.getDurationPerChunk());
		m.addProperty(CHUNKS_PER_FRAG, sadf.getNoOfChunksPerFrag());
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 1);
		m.addProperty("time_started", stream.getTimeStarted() - prebuffer);
		m.addProperty("source", broadcasterAddress);
		m.setAppID(APP_ID);
		host.createNewMessage(m); //must override, meaning start a broadcast that a stream is initiated from this peer
		
		lastChokeInterval = SimClock.getTime();
		lastOptimalInterval = SimClock.getTime();
		super.sendEventToListeners(BROADCAST_LIVE, null, host);
	}

	private void timeToAskNew(DTNHost host){
//		tempInteger
//		tempHost.clear();
//		tempHost.addAll(availableNeighbors);
		Collections.sort(availableNeighbors, StreamingApplication.BandwidthComparator);
		
		int maxRequestPerNode = maxPendingRequest/availableNeighbors.size();

		for (DTNHost to: availableNeighbors){
			tempInteger.clear();
			tempInteger.addAll(neighborData.get(to));
			tempInteger.removeAll(stream.getBuffermap());
			tempInteger.removeAll(chunkRequest.keySet());
//			
			ArrayList<Integer> toRequest = new ArrayList<Integer>();
			if (tempInteger.size() > maxRequestPerNode){
				if (maxRequestPerNode+chunkRequest.size() > maxPendingRequest){
					toRequest.addAll(tempInteger.subList(0, (maxPendingRequest-chunkRequest.size())));
				}
				else{
					toRequest.addAll(tempInteger.subList(0, maxRequestPerNode));
				}
			}
			else{
				toRequest = tempInteger;
			}

			for(int chunkId: toRequest){
				//expiry = 10 seconds before this will be played
				double expiry = (((chunkId*stream.getByterate()) - (stream.getPlaying()*stream.getByterate()))+
						SimClock.getTime()) - waitingThreshold;
				if (expiry <= SimClock.getTime() ){
					expiry = SimClock.getTime() + waitingThreshold;
				}
				chunkRequest.put(chunkId, expiry);
			}

			if (!toRequest.isEmpty()){
				sendRequest(host,to,toRequest);
				sendEventToListeners(StreamAppReporter.SENT_REQUEST, toRequest,host); //di pa ak sure kun diin ini dapat
			}
			
		}
	}
	
	private boolean isInterested(int ack, ArrayList<Integer> otherHas){
		if (stream.getAck() < ack){ //naive, not yet final
			return true;
		}
		tempInteger.clear();
		tempInteger.addAll(new ArrayList<Integer>(otherHas));
		tempInteger.removeAll(stream.getBuffermap());
		tempInteger.removeAll(chunkRequest.keySet());
		return !tempInteger.isEmpty();
	}
	
	/*
	 * called everytime availableNeighbors is updated
	 */
	private void requestFromNeighbors(DTNHost host, DTNHost otherNode){ 
		
		if (stream.getBuffermap().isEmpty() && chunkRequest.isEmpty() && status==WAITING){
			sendEventToListeners(StreamAppReporter.FIRST_TIME_REQUESTED, SimClock.getTime(), host);
		}
				
		maxRequestPerNode = maxPendingRequest/availableNeighbors.size(); //be sure what really happens with this.

		tempInteger.clear();
		tempInteger.addAll(new ArrayList<Integer>(neighborData.get(otherNode)));
		tempInteger.removeAll(stream.getBuffermap());
		tempInteger.removeAll(chunkRequest.keySet());
		
		ArrayList<Integer> toRequest = new ArrayList<Integer>();
		if (tempInteger.size() > maxRequestPerNode){
			if (maxRequestPerNode+chunkRequest.size() > maxPendingRequest){
				toRequest.addAll(tempInteger.subList(0, (maxPendingRequest-chunkRequest.size())));
			}
			else{
				toRequest.addAll(tempInteger.subList(0, maxRequestPerNode));
			}
		}
		else{
			toRequest.addAll(tempInteger);
		}

		for(int chunkId: toRequest){
			//expiry = 10 seconds before this will be played
			double expiry = (((chunkId*stream.getDurationPerChunk()) - (stream.getPlaying()*stream.getDurationPerChunk()))+
					SimClock.getTime()) - waitingThreshold;
			if (expiry <= SimClock.getTime() ){
				expiry = SimClock.getTime() + waitingThreshold;
			}
			chunkRequest.put(chunkId, expiry);
		}

		if (!toRequest.isEmpty()){
			System.out.println(host + " TO REQUEEEEST: " + toRequest + " to_ " + otherNode);
			sendRequest(host,otherNode, toRequest);
			sendEventToListeners(StreamAppReporter.SENT_REQUEST,toRequest,host); //di pa ak sure kun diin ini dapat
		}

	}

	private void sendBuffermap(DTNHost host, DTNHost to, ArrayList<Integer> buffermap){  // ArrayList<Fragment> fragments){
//		System.out.println(host + " sent hello to "+to + " buffermap: " + buffermap);
		String id = APP_TYPE+ ":hello_" + SimClock.getTime() +"-" +host.getAddress() +"-" + to;
		
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
	
	private void sendInterested(DTNHost host, DTNHost to, boolean isInterested) {
		String id;
		String msgType;
		if (isInterested){
			id = APP_TYPE + ":interested_" + SimClock.getIntTime() +"-" + host + "-" +to;
			msgType= INTERESTED;
		}
		else{
			id = APP_TYPE + ":uninterested_" + SimClock.getIntTime() + "-" + host + "-" +to;
			msgType=UNINTERESTED;
		}
		
		Message m = new Message(host, to, id, SIMPLE_MSG_SIZE);
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type",msgType);
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 3);
		host.createNewMessage(m);

		sendEventToListeners(INTERESTED, null, host);
	}
	
	private void sendRequest(DTNHost host, DTNHost to, ArrayList<Integer> chunkNeeded){
		String id = APP_TYPE + ":request_" + chunkNeeded  + "-" + host.getAddress()+"-"+ to;
		
		Message m = new Message(host, to, id, SIMPLE_MSG_SIZE);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", BROADCAST_REQUEST);
		m.addProperty("chunk", chunkNeeded); //new ArrayList<Long>()
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 2);
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
	 * sends chunk individually
	 */
	private void sendWithoutFrag(DTNHost host, Message msg){
		ArrayList<Integer> request = (ArrayList<Integer>) msg.getProperty("chunk");
		
//		System.out.println(msg.getFrom() + " REQUESTED: " + request);
		for (int rChunk: request){
			sendChunk(stream.getChunk(rChunk), host, msg.getFrom());
		}
	}
	
	private void evaluateToSend(DTNHost host, Message msg) {
		ArrayList<Integer> request = (ArrayList<Integer>) msg.getProperty("chunk");
		tempInteger.clear(); //temporary storage for chunks to send
		
		int rChunk;
		int currFrag;
		
		while(!request.isEmpty()){
			rChunk = request.get(0);
			currFrag = stream.getChunk(rChunk).getFragmentIndex();
			tempInteger.clear();
			
			if ( sadf.doesExist(currFrag) && sadf.getFragment(currFrag).isComplete() ){ //send fragments
				int fChunk = sadf.getFragment(currFrag).getFirstChunkID();
				int lChunk = sadf.getFragment(currFrag).getEndChunk();
				
				//if request contains full fragment
				if (request.contains(fChunk) && request.contains(lChunk) && 
					request.subList(request.indexOf(fChunk), request.indexOf(lChunk)+1).size() == sadf.getFragment(currFrag).getNoOfChunks()){
						
					//check if fragment size is greater than tran size
					if (isGreaterThanTrans(host, msg.getFrom(), (sadf.getFragment(currFrag).getNoOfChunks()*stream.getByterate())+HEADER_SIZE)){ //fragment with respect to trans size
						tempInteger = subFragment(host, msg.getFrom(), sadf.getFragment(currFrag).getBundled(), currFrag);
						request.removeAll(request.subList(request.indexOf(fChunk), request.indexOf(lChunk)+1));
					}
					else{ //if fragment size<=trans size
						int sIndex = sadf.getFragment(currFrag).indexOf(fChunk);
						int lIndex= sadf.getFragment(currFrag).indexOf(lChunk);
						sendIndexFragment(host, msg.getFrom(), sadf.getFragment(currFrag).getBundled(), currFrag, sIndex, lIndex);
						request.removeAll(request.subList(request.indexOf(fChunk), request.indexOf(lIndex)+1));
					}
				}
				
				else{ //if request is not a full fragment
					
//					long prevChunk= rChunk;
//					Iterator<Long> iter = request.iterator();
//					
//					double transSize = 	getTransSize(host, msg.getFrom());
//					double byteRate = stream.getByterate();
//					double currSize;
//					long currChunk;
//					
//					while(iter.hasNext()){
//						currChunk = iter.next();
//						currSize = (tempInteger.size()*byteRate) + HEADER_SIZE;
//					
//						if(currFrag == stream.getChunk(currChunk).getFragmentIndex() && (tempInteger.isEmpty() || currChunk==prevChunk+1)
//								&& currSize < transSize){
//							tempInteger.add(currChunk);
//							prevChunk = currChunk;
//							iter.remove();
//						}
//						else{
//							if (currSize > transSize){
//								sendEventToListeners(StreamAppReporter.SIZE_ADJUSTED, null, host);
//							}
//							break;
//						}
					
					tempInteger = subFragment(host, msg.getFrom(), sadf.getFragment(currFrag).getBundled(), currFrag);
				}
					
				if (tempInteger.size()>1){ //
					int start = sadf.getFragment(currFrag).indexOf(tempInteger.get(0));
					int end = sadf.getFragment(currFrag).indexOf(tempInteger.get(tempInteger.size()));
					
					sendTransFragment(host, msg.getFrom(), tempInteger, currFrag, start, end);
				}
				else if (tempInteger.size()==1){ //limit trans level == 2 chunks fragmented
					sendChunk(stream.getChunk(tempInteger.get(0)), host, msg.getFrom());
				}
				else{
					sendChunk(stream.getChunk(rChunk), host, msg.getFrom());
				}
				
			}
			
			else{ //if fragment does not exist, send chunk individually
				sendChunk(stream.getChunk(rChunk), host, msg.getFrom());
				request.remove(rChunk);
			}
		}
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

	private void sendIndexFragment(DTNHost host, DTNHost to, List<Integer> bundled, int fragId,
			int startPos, int endPos) {
		
		String id = APP_TYPE + ":fragment_" + SimClock.getTime() + "-"+  fragId + "-" + to + "-" + host.getAddress();
		double size = (bundled.size()*stream.getByterate()) + HEADER_SIZE;
		
		Message m = new Message(host, to, id,  (int) size);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", BROADCAST_FRAGMENT_SENT);
		m.addProperty("frag_type", SADFragmentation.INDEX_LEVEL);
		m.addProperty("frag_id", fragId);
		m.addProperty("frag_bundled", bundled);
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 2);
		host.createNewMessage(m);

		sendEventToListeners(StreamAppReporter.SENT_INDEX_FRAGMENT, null, host);
	}
	
	private void sendTransFragment(DTNHost host, DTNHost to,  ArrayList<Integer> tempInteger, int fragId,
			int startPos, int endPos) {
		
		String id = APP_TYPE + ":fragment_"  + SimClock.getTime() + "-" + fragId + "-" + to + "-" + host.getAddress();

		double size = (tempInteger.size()*stream.getByterate()) + HEADER_SIZE;
		
		Message m = new Message(host, to, id, (int) size);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", BROADCAST_FRAGMENT_SENT);
		m.addProperty("frag_type", SADFragmentation.TRANS_LEVEL);
		m.addProperty("frag_id", fragId);
		m.addProperty("frag_bundled", tempInteger);
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 2);
		host.createNewMessage(m);

		sendEventToListeners(StreamAppReporter.SENT_TRANS_FRAGMENT, null, host);
	}
	
	/*
	 * called if total chunks to send is greater than translevel
	 */
	private ArrayList<Integer> subFragment(DTNHost host, DTNHost to, ArrayList<Integer> bundle, int currFrag){
		sendEventToListeners(StreamAppReporter.SIZE_ADJUSTED, null, host);
		tempInteger.clear(); //temporary storage for chunks to send
		
		double transSize = 	getTransSize(host, to);
		double byteRate = stream.getByterate();
		double currSize;
		int prevChunk;
		
		Iterator<Integer> iter = bundle.iterator();
		prevChunk = bundle.get(0);
		
		int currChunk;
		while(iter.hasNext()){
			currChunk = iter.next();
			currSize = ((tempInteger.size()+1)*byteRate) + HEADER_SIZE;

			if(currFrag == stream.getChunk(currChunk).getFragmentIndex() && (tempInteger.isEmpty() || currChunk==prevChunk+1) 
					&& currSize < transSize){
				tempInteger.add(currChunk);
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
		return tempInteger;
	}
	
	private boolean isGreaterThanTrans(DTNHost host, DTNHost to, double size){
		
		double transSize = ((TVProphetRouterV2) host.getRouter()).getTransmissionPreds(to);
		return size>transSize;
	}
	
	public double getTransSize(DTNHost host, DTNHost to){
		return 	((TVProphetRouterV2) host.getRouter()).getTransmissionPreds(to);
	}
	
	public void updateHello(DTNHost host, int newChunk, DTNHost from){
		checkHelloedConnection(host);

//		tempInteger.clear();
//		tempInteger.add(newChunk);
		ArrayList<Integer> temp = new ArrayList<Integer>();
		temp.add(newChunk);
		
		for (DTNHost h: unchoked){
			if (h!=null && !h.equals(from)) {
				sendBuffermap(host, h, temp);
			
//				System.out.println(host + " send update hello" + tempInteger);
			
				try{
					helloSent.get(h).addAll(temp);
				}catch(NullPointerException e){
					this.updateUnchoked(unchoked.indexOf(h), null);
				}
			}
		}
		
		for (DTNHost h: helloSent.keySet()){
			if (!h.equals(broadcasterAddress) && !unchoked.contains(h) && !h.equals(from)){
				sendBuffermap(host, h, temp);
//				System.out.println(host + " send update hello" + tempInteger);

				helloSent.get(h).addAll(temp);
			}
		}
	}		

	
	public void updateChunksAvailable(DTNHost from, ArrayList<Integer> newBuffermap){
//		ArrayList<Long> buffermap = null;
//		tempInteger.clear();
		if (newBuffermap.isEmpty()) return;
		if (neighborData.containsKey(from)){
//			tempInteger.addAll( (ArrayList<Long>) neighborData.get(from).clone());
//			tempInteger.addAll(newBuffermap); //put new buffermap
//			neighborData.put(from, (ArrayList<Long>) tempInteger.clone());
			neighborData.get(from).addAll(newBuffermap);
		}
		else{
			neighborData.put(from, new ArrayList<Integer> (newBuffermap));
		}
//		updateChunkCount(newBuffermap); //increase count of chunks
//		System.out.println(" neighbor data of " + from + ": " + neighborData.get(from));
	}

	/*
	 * 
	 * CHOKE/UNCHOKE STARTS HERE ---------------------->
	 * 
	 */
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
			interestedNeighbors.remove(to); //remove from interestedNeighbors since granted
		}
	}
	
	public void sendResponse(DTNHost host, DTNHost to, boolean isOkay){
		
		String id;
		String msgType; 

		if (isOkay){
			id = APP_TYPE + ":UNCHOKE_" +  SimClock.getIntTime() + "-" + host.getAddress()  +"-" + to;
			msgType = UNCHOKE;
		}
		else{
			id = APP_TYPE + ":CHOKE_" + SimClock.getIntTime()+ "-" + host.getAddress()  + "-" + to;
			msgType = CHOKE;
		}

		tempInteger.clear();
		tempInteger.addAll(stream.getBuffermap());
		tempInteger.removeAll(helloSent.get(to));
		
		Message m = new Message(host, to, id, SIMPLE_MSG_SIZE);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", msgType);
		m.addProperty("buffermap", new ArrayList<Integer>(tempInteger));
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 4);
		host.createNewMessage(m);
	}
	
	/*
	 * count if an sulod han interested is same la ha mga unchoked
	 */
	private boolean hasNewInterested(){

		if (interestedNeighbors.isEmpty()) return false;
			
		tempHost.clear();
		tempHost.addAll(interestedNeighbors);
		tempHost.removeAll(unchoked);
		
		return !tempHost.isEmpty();
	}

	/*
	 * called every 15 seconds.
	 * after this method, unchoked list is updated. recognized includes those na diri na api ha top3
	 */
	private void unchokeTop3(DTNHost host, ArrayList<DTNHost> recognized){
		if (recognized.isEmpty()) return;

		Iterator<DTNHost> i = recognized.iterator();
		
 		for (int ctr=0; ctr<3; ctr++){ //send UNCHOKE to top 3
			DTNHost other=null;
			try{
				other = i.next();	
				if (!unchoked.contains(other) ){ //if diri hya api ha kanina na group of unchoked
					sendResponse(host, other, true); //send UNCHOKE
					interestedNeighbors.remove(other); //for those new interested
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
	private void unchokeRand(DTNHost host, ArrayList<DTNHost> recognized, ArrayList<DTNHost> prevUnchoked){ 	//every 5 seconds. i-sure na diri same han last //tas diri dapat api ha top3
		if (recognized.isEmpty()) return;

		DTNHost prevRand;
//		recognized.removeAll(unchoked.subList(0, 3)); //remove pagpili random an ada na ha unchoked list
		prevRand = unchoked.get(3);
		
		int index = r.nextInt(recognized.size()); //possible ini maging same han last random
		DTNHost randNode = recognized.get(index);

		if (prevRand!=randNode){
			if (prevRand!=null)  sendResponse(host, prevRand, false); //send CHOKE to this random node if it is not the same with new node
			recognized.remove(prevRand);
			if (!prevUnchoked.contains(randNode)){
				sendResponse(host, randNode, true); //sendUnchoke to this random node if it isn't unchoked before
			}
		}
		updateUnchoked(3, randNode);
		interestedNeighbors.remove(randNode);  //notification granted. remove on original interested list
		recognized.remove(randNode); //notification granted. remove on recognized list
	}
	
	private void chokeOthers(DTNHost host, ArrayList<DTNHost> recognized){
		for (DTNHost r : recognized){
			if (r!=null){
				sendResponse(host, r, false); 
			}
		}
	}
	
	public int getWatcherType(){
		return watcherType;
	}
	
	@Override
	public Application replicate() {
		return new WatcherAppV3(this);
	}

}

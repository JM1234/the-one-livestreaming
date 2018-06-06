package applications;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

import core.Application;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import fragmentation.SADFragmentation;
import report.StreamAppReporter;
import routing.TVProphetRouterV2;
import streaming.Stream;
import streaming.StreamChunk;

public class BroadcasterApp extends StreamingApplication {

	public static final String STREAM_TIME = "streamTime";

	private Stream 	stream;
	private SADFragmentation sadf;
	
	private boolean broadcasted=false;
	private static double sTime;
	
	private double timeLastStream;
	private double lastChokeInterval = 0;
	private double lastOptimalInterval =0;
	private static int noOfChunksPerFrag=0; //default=0, no fragmentation
	private static double byterate; //bitrate in bytes
	private static int durationPerChunk;
	private static int windowSize=10;
	private Random r;
	
	private ArrayList<Integer> holdChunk;
	private ArrayList<DTNHost> holdHost;
	private ArrayList<DTNHost> prevUnchoked;
	
	public BroadcasterApp(Settings s) {
		super(s);
		
		noOfChunksPerFrag = s.getInt(CHUNKS_PER_FRAG);
		byterate = s.getInt(BYTERATE);
		durationPerChunk = s.getInt(DURATION_PER_CHUNK);
		
		holdChunk = new ArrayList<Integer>();
		holdHost = new ArrayList<DTNHost>();
		prevUnchoked = new ArrayList<DTNHost>();
		
		if (s.contains(WINDOW_SIZE))
			windowSize = s.getInt(WINDOW_SIZE); //playbackdelay*consumptionrate/chunk size
		
		sTime = 0;	//s.getDouble("streamTime") * r.nextDouble(); //time to start broadcasting
		r = new Random(streamSeed);
		initUnchoke();
	}

	public BroadcasterApp(BroadcasterApp a) {
		super(a);
		
		noOfChunksPerFrag = a.getNumberOfChunksPerFrag();
		byterate = a.getByterate();
		durationPerChunk = a.getDurationPerChunk();
		windowSize = a.getWindowSize();
		
		holdChunk = new ArrayList<Integer>();
		holdHost = new ArrayList<DTNHost>();
		prevUnchoked = new ArrayList<DTNHost>();
		
		sTime = a.getSTime();
		r = new Random(streamSeed);
		initUnchoke();
	}

	@Override
	public Message handle(Message msg, DTNHost host) {
		String type = (String) msg.getProperty("type");
		if (type==null) return msg;
		
		if(type.equals(APP_TYPE)){
			String msg_type = (String) msg.getProperty("msg_type");
			
			if (msg_type.equalsIgnoreCase(HELLO)){
//				System.out.println(host + " received hello " + msg.getFrom());
				int otherAck = (int) msg.getProperty("ack");
				int otherStatus = (int) msg.getProperty("status");
				ArrayList<Integer> otherBuffermap = (ArrayList<Integer>) msg.getProperty("buffermap");
			
//				System.out.println(" otherStatus: " + otherStatus + " ack: " + otherAck);
				ArrayList<Integer> tempH = copyInt(stream.getBuffermap());
				if (broadcasted && otherStatus==-1 && otherAck==-1){
					sendBroadcast(host, msg.getFrom());
					sendBuffermap(host, msg.getFrom(), tempH);
				}
				else{
					sendBuffermap(host, msg.getFrom(), tempH);
				}
				helloSent.put(msg.getFrom(), tempH);				
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
				grantRequest(host);
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
				grantRequest(host);
			}
			
			else if (msg_type.equals(INTERESTED)){
//				System.out.println(host + " received interested from " + msg.getFrom());
				if (!interestedNeighbors.contains(msg.getFrom())){
					interestedNeighbors.add(msg.getFrom());
				}
				evaluateResponse(host, msg.getFrom());	
			}
			
			else if (msg_type.equalsIgnoreCase(UNINTERESTED)){
//				System.out.println(host + " received uninterested from " + msg.getFrom());
				interestedNeighbors.remove(msg.getFrom());
				if (unchoked.contains(msg.getFrom())){
					updateUnchoked(unchoked.indexOf(msg.getFrom()), null);
				}
			}
		}
		
		return msg;
	}

	private void sendWithoutFrag(DTNHost host, DTNHost to){
		ArrayList<Integer> request = receivedRequests.get(to);
		
		for (int id : request){
			sendChunk(stream.getChunk(id), host, to);
		}
		
//		int request = (int) msg.getProperty("chunk");
//		sendChunk(stream.getChunk(request), host, msg.getFrom());
	}
	
	@Override
	public void update(DTNHost host) {
		double curTime = SimClock.getTime();
		
		if (!broadcasted && curTime>=sTime){ //startBroadcast here, once
			startBroadcast(host);
			broadcasted =  true;
		}
		
		checkHelloedConnection(host); //remove data of disconnected nodes
//		grantRequests(host);
		
//		Iterator<Map.Entry<DTNHost, ArrayList<Integer>>> it = receivedRequests.entrySet().iterator();
//		while(it.hasNext()){
//			DTNHost to = it.next().getKey();
//			
//			if (noOfChunksPerFrag>0){
//				evaluateToSend(host, to);
//			}
//			else{
//				sendWithoutFrag(host, to);
//			}
//			it.remove();
//		}
		
		if (broadcasted){
			//generate chunks here
			if ( curTime - timeLastStream >= stream.getDurationPerChunk() ){ //for every interval
				stream.generateChunks(getStreamID(), sadf.getCurrIndex());
				timeLastStream = curTime;

				sendEventToListeners(StreamAppReporter.CHUNK_CREATED, null, host);
				updateHello(host, stream.getAck());
				
//				System.out.println("Generated chunk " + stream.getLatestChunk().getChunkID() + "Fragment: " + stream.getLatestChunk().getFragmentIndex());
			
				//create fragments here
				if (sadf.getNoOfChunksPerFrag()>0){ 
					if((stream.getLatestChunk().getChunkID()+1) % sadf.getNoOfChunksPerFrag() == 0){
			
						int sIndex = sadf.getCurrIndex() * sadf.getNoOfChunksPerFrag();
						sadf.createFragment(stream.getBuffermap().subList(sIndex, sIndex+noOfChunksPerFrag));
						
						try{
							sadf.getFragment(sadf.getCurrIndex()-1).setIndexComplete();
						}catch(NullPointerException e){
							sadf.getFragment(0).setIndexComplete();			
						}
						
						sendEventToListeners(StreamAppReporter.FRAGMENT_CREATED, null, host);
					}
				}
			}
			
			if (curTime-lastChokeInterval >= rechokeInterval){
				updateUnchoke(curTime, host);
			}
		}
		
	}
	
	private void grantRequest(DTNHost host){
		Iterator<Map.Entry<DTNHost, ArrayList<Integer>>> it = receivedRequests.entrySet().iterator();
		
		while(it.hasNext()){
			DTNHost to = it.next().getKey();
			
			if (noOfChunksPerFrag>0){
				evaluateToSend(host, to);
			}
			else{
				sendWithoutFrag(host, to);
			}
			it.remove();
		}
	}
//	private void grantIndivRequests(DTNHost host) {
//
//		for(DTNHost to : receivedRequests.keySet()){
//			ArrayList<Integer> msgs = receivedRequests.get(to);
//			Iterator<Integer> i = msgs.iterator();
////			System.out.println("Request Msgs: " +msgs);
//			while (i.hasNext()){
//				sendChunk(stream.getChunk(i.next()), host, to);
//				i.remove();
//			}
//		}
//	}
//
	private void evaluateToSend(DTNHost host, DTNHost to) {
		ArrayList<Integer> request = receivedRequests.get(to);
		holdChunk.clear(); //temporary storage for chunks to send
		Collections.sort(request);
		
		int rChunk;
		int currFrag;
		
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
					if (isGreaterThanTrans(host, to, ((sadf.getFragment(currFrag).getNoOfChunks()*byterate)+HEADER_SIZE))){ //fragment with respect to trans size
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

//	private void subSurplusTrans(int surplusTrans, ArrayList<Integer> bundle, int currFrag){
//		
//		int prevChunk;
//		Iterator<Integer> iter = bundle.iterator();
//		prevChunk = bundle.get(0);
//		int currChunk;
//		int noOfChunk=-1;
//		
//		while(iter.hasNext()){
//			currChunk = iter.next();
//			
//			if(currFrag == stream.getChunk(currChunk).getFragmentIndex() && (holdChunk.isEmpty() || currChunk==prevChunk+1) 
//					&& noOfChunk < surplusTrans){
//				holdChunk.add(currChunk);
//				prevChunk = currChunk;
//				iter.remove();
//				noOfChunk++;
//			}
//			else{
////				if (currSize > transSize){
////					sendEventToListeners(StreamAppReporter.SIZE_ADJUSTED, null, host);
////				}
//				break;
//			}
//		}
//	}
	
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
			currSize = ((holdChunk.size()+1)*byterate) + HEADER_SIZE;

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
		double size = (bundled.size()*byterate) + HEADER_SIZE;
		
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

		double size = (bundled.size()*byterate) + HEADER_SIZE;
		
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
	
	private void sendChunk(StreamChunk chunk, DTNHost host, DTNHost to) {
//		System.out.println(host + " sending chunk " + chunk.getChunkID());
		
		String id = APP_TYPE + ":chunk_" + chunk.getChunkID() +  " " + chunk.getCreationTime() +"-" +to + "-" + host.getAddress();
		
		Message m = new Message(host, to, id, (int) byterate);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", BROADCAST_CHUNK_SENT);
		m.addProperty("chunk", chunk);	
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 2);
		host.createNewMessage(m);

		sendEventToListeners(StreamAppReporter.SENT_CHUNK, chunk, host);
	}
	
	public void sendBuffermap(DTNHost host, DTNHost to, ArrayList<Integer> list){
//		System.out.println(host + " sentHello to " + to);
		String id = APP_TYPE+ ":hello_" + SimClock.getFormattedTime(2) +"-" + host.getAddress() +"-" + to;
		
		int ack =-1;
		if(!stream.getBuffermap().isEmpty()){
			ack = stream.getAck();
		}
		
		Message m = new Message(host, to, id, BUFFERMAP_SIZE); //buffermap size must have basis
		m.setAppID(APP_ID);
		m.addProperty("type", APP_TYPE);
		m.addProperty("msg_type", HELLO);
		m.addProperty("status", 2);
		m.addProperty("ack", ack);
		m.addProperty("buffermap", list); // this is full buffermap
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 5);
		host.createNewMessage(m);
	}

	public void sendBroadcast(DTNHost host, DTNHost to){
//		System.out.println(host + " sending broadcast." + to);
		String id = APP_TYPE + ":broadcast" + SimClock.getFormattedTime(2) + "-" + host.getAddress() + "-" + to;
		
		Message m= new Message(host, to, id, SIMPLE_MSG_SIZE);
		m.addProperty("type", APP_TYPE);
		m.addProperty("msg_type", BROADCAST_LIVE);
		m.addProperty("streamID", getStreamID());
		m.addProperty("stream_name", "streamName");
		m.addProperty(BYTERATE, byterate);
		m.addProperty(DURATION_PER_CHUNK, durationPerChunk);
		m.addProperty(CHUNKS_PER_FRAG, noOfChunksPerFrag);
		m.addProperty(WINDOW_SIZE, stream.getWindowSize());
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 1);
		m.addProperty("time_started", sTime);
		m.addProperty("source", host);
		
		m.setAppID(APP_ID);
		host.createNewMessage(m); //a broadcast is initiated from this peer
		
		lastChokeInterval = SimClock.getTime();
		lastOptimalInterval = SimClock.getTime();
		super.sendEventToListeners(BROADCAST_LIVE, null, host);
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
			id = APP_TYPE + ":UNCHOKE_" +  SimClock.getFormattedTime(2) + "-" + host.getAddress()  +"-" + to;
			msgType = UNCHOKE;
		}
		else{
			id = APP_TYPE + ":CHOKE_" + SimClock.getFormattedTime(2) + "-" + host.getAddress()  + "-" + to;
			msgType = CHOKE;
		}

		ArrayList<Integer> tempChunk = copyInt(stream.getBuffermap());
		if (helloSent.containsKey(to)) tempChunk.removeAll(helloSent.get(to));
		try{
			helloSent.get(to).addAll(tempChunk);
		}catch(NullPointerException e){
			helloSent.put(to, tempChunk);
		}

		Message m = new Message(host, to, id, SIMPLE_MSG_SIZE);		
		m.addProperty("type", APP_TYPE);
		m.setAppID(APP_ID);
		m.addProperty("msg_type", msgType);
		m.addProperty("buffermap", tempChunk);
		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 4);
		host.createNewMessage(m);
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
//		System.out.println(" interested: " + interestedNeighbors);
	}
	
	/*
	 * count if an sulod han interested is same la ha mga unchoked
	 */
	private boolean hasNewInterested(){
		if (interestedNeighbors.isEmpty()) return false;
//		System.out.println(" interested neighbors: " + interestedNeighbors);
		
		ArrayList<DTNHost> interested = copyHost(interestedNeighbors); //storage for interested host that are not yet unchoke
		interested.removeAll(unchoked);
		return !interested.isEmpty();
	}
	
	public void updateUnchoke(double curTime, DTNHost host){ //for maintaining -- choking and unchoking
//		System.out.println(" prev uchoked: "+ unchoked);
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

	
	public void updateHello(DTNHost host, int newChunk){
		holdChunk.clear(); //temp hold for latest chunk created
		holdChunk.add(newChunk);

		for (DTNHost h: unchoked){
			if (h!=null){
				sendBuffermap(host, h, copyInt(holdChunk));
				helloSent.get(h).add(newChunk);
			}
		}
		
		for (DTNHost h: helloSent.keySet()){
			if (!unchoked.contains(h)){
				sendBuffermap(host, h, copyInt(holdChunk));
				helloSent.get(h).add(newChunk);
			}
		}
	}	
	
	public void startBroadcast(DTNHost host){
		//window size is == 10
		stream= new Stream("streamID", durationPerChunk, byterate, SimClock.getTime(),windowSize);
		stream.startLiveStream();
		sadf = new SADFragmentation();
		sadf.setNoOfChunksPerFrag(noOfChunksPerFrag);
		
		lastChokeInterval = SimClock.getTime();
		lastOptimalInterval = SimClock.getTime();
		super.sendEventToListeners(BROADCAST_LIVE, null, host);
	}
	
	@Override
	public Application replicate() {
		return new BroadcasterApp(this);
	}

	public double getSTime(){
		return sTime;
	}
	  
	private int getDurationPerChunk() {
		return durationPerChunk;
	}

	private double getByterate() {
		return byterate;
	}

	private int getNumberOfChunksPerFrag() {
		return noOfChunksPerFrag;
	}
	
	private int getWindowSize() {
		return windowSize;
	}
}

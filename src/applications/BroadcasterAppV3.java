package applications;

import java.util.ArrayList;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

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

public class BroadcasterAppV3 extends StreamingApplication{

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
	private Random r;
	
	private ArrayList<Integer> tempInteger;
	private ArrayList<DTNHost> tempHost; 
	private ArrayList<DTNHost> recognized;
	
	public BroadcasterAppV3(Settings s) {
		super(s);
		
		noOfChunksPerFrag = s.getInt(CHUNKS_PER_FRAG);
		byterate = s.getInt(BYTERATE);
		durationPerChunk = s.getInt(DURATION_PER_CHUNK);
		
		tempInteger = new ArrayList<Integer>();
		tempHost = new ArrayList<DTNHost>();
		recognized = new ArrayList<DTNHost>();
		
		sTime = 0;	//s.getDouble("streamTime") * r.nextDouble(); //time to start broadcasting
		r = new Random();
		initUnchoke();

	}

	public BroadcasterAppV3(BroadcasterAppV3 a) {
		super(a);
		
		noOfChunksPerFrag = a.getNumberOfChunksPerFrag();
		byterate = a.getByterate();
		durationPerChunk = a.getDurationPerChunk();
		
		tempInteger= new ArrayList<Integer>();
		tempHost = new ArrayList<DTNHost>();
		recognized = new ArrayList<DTNHost>();
		
		sTime = a.getSTime();
		r = new Random();
		initUnchoke();

	}

	@Override
	public Message handle(Message msg, DTNHost host) {	
		String type = (String) msg.getProperty("type");
		if (type==null) return msg;
		
			if(type.equals(APP_TYPE)){
				String msg_type = (String) msg.getProperty("msg_type");
				
				if (msg_type.equalsIgnoreCase(HELLO)){
//					System.out.println(host + " received hello from " +msg.getFrom());
					
					int otherAck = (int) msg.getProperty("ack");
					int otherStatus = (int) msg.getProperty("status");
					ArrayList<Integer> otherBuffermap = (ArrayList<Integer>) msg.getProperty("buffermap");
					
//					updateChunkCount(otherBuffermap); //update records of neighbor's data
				
					if (broadcasted && otherStatus==-1 && otherAck==-1){ //if otherNode is not listening to any stream yet
						sendBroadcast(host, msg.getFrom());
						sendBuffermap(host, msg.getFrom(), new ArrayList<Integer>(stream.getBuffermap()));
						helloSent.put(msg.getFrom(), new ArrayList<Integer> (stream.getBuffermap()));
					}
					
					else {
						sendBuffermap(host, msg.getFrom(), new ArrayList<Integer>(stream.getBuffermap()));
						helloSent.put(msg.getFrom(), stream.getBuffermap());
					}
				}
				
				else if (msg_type.equalsIgnoreCase(BROADCAST_REQUEST)){ //evaluate here if fragment or chunk it isesend
//					System.out.println(host + " received request from " + msg.getFrom());
					
					if (sadf.getNoOfChunksPerFrag() == 0 ){
						sendWithoutFrag(host, msg);
					}
					else{
						evaluateToSend(host, msg);
					}
				}
				
				else if (msg_type.equals(INTERESTED)){
//					System.out.println(host + " received interested from " + msg.getFrom());
					interestedNeighbors.add(msg.getFrom());
					evaluateResponse(host, msg.getFrom());	
				}
				
				else if (msg_type.equalsIgnoreCase(UNINTERESTED)){
//					System.out.println(host + " received uninterested from " + msg.getFrom());
					interestedNeighbors.remove(msg.getFrom());
					if (unchoked.contains(msg.getFrom())){
						updateUnchoked(unchoked.indexOf(msg.getFrom()), null);
					}
				}
			}
		return msg;
	}
	
	@Override
	public void update(DTNHost host) {
		double curTime = SimClock.getTime();
		
		if (!broadcasted && curTime>=sTime){ //startBroadcast here, once
			startBroadcast(host);
			broadcasted =  true;
		}
		
		checkHelloedConnection(host); //remove data of disconnected nodes
		updateUnchoke(curTime, host);
		
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
//							System.out.println(host + " created fragment " + (sadf.getCurrIndex()-1));
						}catch(NullPointerException e){
							sadf.getFragment(0).setIndexComplete();			
//							System.out.println(host + " created fragments " + (sadf.getCurrIndex()-1));
						}
						sendEventToListeners(StreamAppReporter.FRAGMENT_CREATED, null, host);
					}
				}
			}
		}
	
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

	private void sendIndexFragment(DTNHost host, DTNHost to, ArrayList<Integer> bundled, int fragId,
			int startPos, int endPos) {
		
		String id = APP_TYPE + ":fragment_" + SimClock.getTime() + "-"+  fragId + "-" + to + "-" + host.getAddress();
		double size = (bundled.size()*byterate) + HEADER_SIZE;
		
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

		double size = (tempInteger.size()*byterate) + HEADER_SIZE;
		
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
	
	public void updateUnchoke(double curTime, DTNHost host){ //for maintaining -- choking and unchoking
	
		if (curTime-lastChokeInterval >= rechokeInterval){
//			System.out.println(" prev uchoked: "+ unchoked);
			if (hasNewInterested()){
				recognized.clear();
				recognized.addAll(interestedNeighbors);
//				System.out.println(" recognized: " + recognized);
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
	
	public void startBroadcast(DTNHost host){
//		stream= new Stream("streamID", durationPerChunk, byterate, SimClock.getTime());
		stream.startLiveStream();
		sadf = new SADFragmentation();
		sadf.setNoOfChunksPerFrag(noOfChunksPerFrag);
		
		lastChokeInterval = SimClock.getTime();
		lastOptimalInterval = SimClock.getTime();
		super.sendEventToListeners(BROADCAST_LIVE, null, host);
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
		m.addProperty("time_started", stream.getTimeStarted());
		m.addProperty("source", host);
		m.setAppID(APP_ID);
		host.createNewMessage(m); //a broadcast is initiated from this peer
		
		lastChokeInterval = SimClock.getTime();
		lastOptimalInterval = SimClock.getTime();
		super.sendEventToListeners(BROADCAST_LIVE, null, host);
	}
	
	public void sendBuffermap(DTNHost host, DTNHost to, ArrayList<Integer> list){
		String id = APP_TYPE+ ":hello_" + SimClock.getTime() +"-" + host.getAddress() +"-" + to;
		
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
	
	public void updateHello(DTNHost host, int newChunk){
		tempInteger.clear(); //temp hold for latest chunk created
		tempInteger.add(newChunk);

		for (DTNHost h: unchoked){
			if (h!=null){
				sendBuffermap(host, h, new ArrayList<Integer>(tempInteger));
				helloSent.get(h).add(newChunk);
			}
		}
		
		for (DTNHost h: helloSent.keySet()){
			if (!unchoked.contains(h)){
				sendBuffermap(host, h, new ArrayList<Integer>(tempInteger));
				helloSent.get(h).addAll(tempInteger);
			}
		}
		
	}	
	
	private void sendWithoutFrag(DTNHost host, Message msg){
		ArrayList<Integer> request = (ArrayList<Integer>) msg.getProperty("chunk");
//		Iterator<Long> i = request.iterator();
		for (int id : request){
			sendChunk(stream.getChunk(id), host, msg.getFrom());
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
					if (isGreaterThanTrans(host, msg.getFrom(), ((sadf.getFragment(currFrag).getNoOfChunks()*byterate)+HEADER_SIZE))){ //fragment with respect to trans size
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
					
//					int prevChunk= rChunk;
//					Iterator<Long> iter = request.iterator();
//					
//					double transSize = 	getTransSize(host, msg.getFrom());
//					double byteRate = stream.getByterate();
//					double currSize;
//					int currChunk;
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
					
					sendTransFragment(host, msg.getFrom(), new ArrayList<Integer>(tempInteger), currFrag, start, end);
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
	
	/*
	 * called if total chunks to send is greater than translevel
	 */
	private ArrayList<Integer> subFragment(DTNHost host, DTNHost to, ArrayList<Integer> bundle, int currFrag){
		sendEventToListeners(StreamAppReporter.SIZE_ADJUSTED, null, host);
		tempInteger.clear(); //temporary storage for chunks to send
		
		double transSize = 	getTransSize(host, to);
		double currSize;
		int prevChunk;
		
		Iterator<Integer> iter = bundle.iterator();
		prevChunk = bundle.get(0);
		
		int currChunk;
		while(iter.hasNext()){
			currChunk = iter.next();
			currSize = ((tempInteger.size()+1)*byterate) + HEADER_SIZE;

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
	
//	@Override
//	protected void sendChunk(StreamChunk chunk, DTNHost host, DTNHost to) {
//		String id = APP_TYPE + ":chunk_" + chunk.getChunkID() +  " " + chunk.getCreationTime() +"-" +to + "-" + host.getAddress();
//		
//		Message m = new Message(host, to, id, (int) chunk.getSize());		
//		m.addProperty("type", APP_TYPE);
//		m.setAppID(APP_ID);
//		m.addProperty("msg_type", BROADCAST_CHUNK_SENT);
//		m.addProperty("chunk", chunk);	
//		m.addProperty(TVProphetRouterV2.MESSAGE_WEIGHT, 2);
//		host.createNewMessage(m);
//
//		sendEventToListeners(StreamAppReporter.SENT_CHUNK, chunk, host);
//	}
	
	
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

	/*
	 * 
	 * @param isOkay true if response is unchoke. false if choke
	 * 
	 */
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
		tempInteger.removeAll(helloSent.get(to)); //storage for buffermap that has not been sent since last update 
		
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
		tempHost.removeAll(unchoked); //storage for interested host that are not yet unchoke
		
		return !tempHost.isEmpty();
	}

	
	// ----------------------> T R A C E H E R E --------------------------------
	
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
	private void unchokeRand(DTNHost host, ArrayList<DTNHost> recognized, ArrayList<DTNHost> prevUnchoked){
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
	
	private void chokeOthers(DTNHost host, ArrayList<DTNHost> recognized){ 	//sendChoke to tanan na nabilin
		for (DTNHost r : recognized){
			if (r!=null){
				sendResponse(host, r, false); 
			}
		}
	}

	@Override
	public Application replicate() {
		return new BroadcasterAppV3(this);
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
}

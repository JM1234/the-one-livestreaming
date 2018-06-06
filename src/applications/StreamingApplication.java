package applications;

import java.util.ArrayList;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import core.Application;
import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import routing.TVProphetRouterV2;
import util.Tuple;

public abstract class StreamingApplication extends Application{
	
	public static final String APP_ID = "cmsc.janz.StreamingApplication";
	public static final String APP_TYPE = "dtnlivestreaming";
	
	public static final String BROADCAST_LIVE = "BROADCAST_LIVE";
	public static final String BROADCAST_REQUEST = "REQUEST_STREAM";
	public static final String BROADCAST_CHUNK_SENT = "CHUNK_SENT";
	public static final String BROADCAST_FRAGMENT_SENT = "SENT_FRAGMENT";
	public static final String HELLO = "HELLO";
	public static final String INTERESTED = "INTERESTED";
	public static final String UNINTERESTED = "UNINTERESTED";
	public static final String CHOKE = "CHOKED";
	public static final String UNCHOKE = "UNCHOKED";
	public static final String BROADCAST_BUNDLED_REQUEST = "BROADCAST_BUNDLED_REQUEST";
	
	public static final String STREAM_ID = "streamID";
	public static final String STREAM_NAME = "streamName";
	public static final String RECHOKE_INTERVAL = "rechokeInterval";
	public static final String OPTIMISTIC_UNCHOKE_INTERVAL = "optimisticUnchokeInterval";
	public static final String CHUNKS_PER_FRAG = "noOfChunksPerFrag";
	public static final String BYTERATE = "byterate";
	public static final String DURATION_PER_CHUNK = "durationPerChunk"; //seconds only
	public static final String WINDOW_SIZE = "windowSize";
	
	public static final int SIMPLE_MSG_SIZE = 5;
	public static final int BUFFERMAP_SIZE = 10;
	public static final int HEADER_SIZE = 56;
	public static final int INDEX_TYPE = 1;
	public static final int TRANS_TYPE = 2;
	
	private String	streamID = "9999";
	
	protected HashMap<DTNHost, ArrayList<Integer>> helloSent; //nodes we sent hello to
	protected ArrayList<DTNHost> interestedNeighbors; //nodes that can request from us
	protected ArrayList<DTNHost> unchoked; //nodes that we unchoked
	///////////////remove data of this when host is gone
	protected HashMap<DTNHost, ArrayList<Integer>> receivedRequests;
	protected HashMap<DTNHost, Integer> surplus;

	protected static int rechokeInterval;
	protected static int optimisticUnchokeInterval;
	
//	private TreeMap<Long, Integer> chunkCount; //for rarest
	private ArrayList<DTNHost> currConnected;
	private ArrayList<DTNHost> tempHoldHost;
	
	public StreamingApplication(Settings s){

		if(s.contains(STREAM_ID)){
			this.streamID = s.getSetting(STREAM_ID);			
		}
		
		rechokeInterval = s.getInt(RECHOKE_INTERVAL);
		optimisticUnchokeInterval = s.getInt(OPTIMISTIC_UNCHOKE_INTERVAL);
		
		helloSent = new HashMap<DTNHost, ArrayList<Integer>>();
		interestedNeighbors = new ArrayList<DTNHost>();
		unchoked = new ArrayList<DTNHost>(4);
		receivedRequests = new HashMap<DTNHost, ArrayList<Integer>>();
		surplus = new HashMap<DTNHost, Integer>();
		
		currConnected= new ArrayList<DTNHost>();
		tempHoldHost = new ArrayList<DTNHost>();
		
		super.setAppID(APP_ID);
	}
	
	public StreamingApplication(StreamingApplication a){
		super(a);
		
		streamID = a.getStreamID();
		rechokeInterval = a.getRechokeInterval();
		optimisticUnchokeInterval = a.getOptimisticUnchokeInterval();
		
		helloSent = new HashMap<DTNHost, ArrayList<Integer>>();
		interestedNeighbors = new ArrayList<DTNHost>();
		unchoked = new ArrayList<DTNHost>(4);
		receivedRequests = new HashMap<DTNHost, ArrayList<Integer>>();
		surplus = new HashMap<DTNHost, Integer>();
		
		currConnected= new ArrayList<DTNHost>();
		tempHoldHost = new ArrayList<DTNHost>();

	}

	private int getOptimisticUnchokeInterval() {
		return optimisticUnchokeInterval;
	}

	private int getRechokeInterval() {
		return rechokeInterval;
	}
	
	public String getStreamID(){
		return streamID;
	}

	@Override
	public abstract Message handle(Message msg, DTNHost host);

	@Override
	public abstract void update(DTNHost host);
	
//	protected abstract void sendChunk(StreamChunk chunk, DTNHost host, DTNHost to);

	protected Connection getCurrConnection(DTNHost h1, DTNHost h2){
		for(Connection c: h1.getConnections()){
			if ((c.getOtherNode(h1)).equals(h2)){
				return c;
			}
		}
		return null;
	}
	
	/*
	 * Checks if there are changes in connection.
	 * Delete hosts in sentHello that are already down.
	 * Automatically removes buffer for disconnected nodes.
	 * returns nodes that are already disconnected
	 * 
	 */
	protected ArrayList<DTNHost> checkHelloedConnection(DTNHost host){ 
		currConnected.clear();
		tempHoldHost.clear();

		for (Connection c : host.getConnections()){
			currConnected.add(c.getOtherNode(host));
		}

		tempHoldHost.addAll(new ArrayList<DTNHost> (helloSent.keySet()));
		tempHoldHost.removeAll(currConnected);

	    for(DTNHost dtnHost : tempHoldHost){
			removeBufferedMessages(host, dtnHost);
			interestedNeighbors.remove(dtnHost); //if it sent an interested message, remove it from the list of interested
			updateUnchoked(unchoked.indexOf(dtnHost), null); //if it is included among the current list of unchoked  -----------------------feeling ko may something wrong ini
			helloSent.remove(dtnHost);
			receivedRequests.remove(dtnHost);
			surplus.remove(dtnHost);
	    }	
	    return tempHoldHost;
	}

	protected ArrayList<DTNHost> getNewConnections(){
		currConnected.removeAll(new ArrayList<DTNHost> (helloSent.keySet()));
		return currConnected;
	}
	
	/*
	 * Remove buffered messages for the to host
	 */
	private void removeBufferedMessages(DTNHost host, DTNHost to){
		List<Tuple<Message, Connection>> msgs = ((TVProphetRouterV2) host.getRouter()).getMessagesForConnected();
		
		for(Tuple<Message, Connection> m : msgs){
			if (m.getValue().getOtherNode(host).equals(to)){ //remove the messages in the buffer intended for the 'to' node
				Message stored = m.getKey();
				host.deleteMessage(stored.getId(), false);
			}
		}
		return;
	}
	
	public boolean hasHelloed(DTNHost host){
		return helloSent.keySet().contains(host);
	}

	
//	protected void updateChunkCount(ArrayList<Long> buffermap){
//		for (long id : buffermap){
//			int count = chunkCount.containsKey(id) ? chunkCount.get(id):0;
//			chunkCount.put(id, count+1);
//		}
//	}
//	
//	public TreeMap<Long, Integer> getChunkCount(){
//		entriesSortedByValues(chunkCount);
//		return chunkCount;
//	}
	
	public static <K,V extends Comparable<? super V>>
		SortedSet<Map.Entry<K,V>> entriesSortedByValues(Map<K,V> map) {
	  
		SortedSet<Map.Entry<K,V>> sortedEntries = new TreeSet<Map.Entry<K,V>>(
	        new Comparator<Map.Entry<K,V>>() {
	            @Override public int compare(Map.Entry<K,V> e1, Map.Entry<K,V> e2) {
	                int res = e1.getValue().compareTo(e2.getValue());
	                return res != 0 ? res : 1; //i think this is ascending
	            }
	        }
	    );
	    sortedEntries.addAll(map.entrySet());
	    return sortedEntries;
	}

//	public ArrayList<DTNHost> sortNeighborsByBandwidth(ArrayList<DTNHost> hosts){
//		Collections.sort(hosts, StreamingApplication.BandwidthComparator);
//		return hosts;
//	}
	
	public void sortNeighborsByBandwidth(List<DTNHost> hosts){
		Collections.sort(hosts, StreamingApplication.BandwidthComparator);
	}

    public static Comparator<DTNHost> BandwidthComparator = new Comparator<DTNHost>() {
    	public int compare(DTNHost h1, DTNHost h2) { //descending order
    		int speed1 = h1.getInterface(1).getTransmitSpeed(h1.getInterface(1));
    		int speed2 = h2.getInterface(1).getTransmitSpeed(h2.getInterface(1));
	    	
    		if (speed2>speed1){
    			return 1;
    		}
    		else if (speed2<speed1){
    			return -1;
    		}
    		return 0;
    	}
	};

    public void updateUnchoked(int index, DTNHost value){
    	try{
    		this.unchoked.set(index, value); // change value of unchoke to null if toberemoved
    	}catch(ArrayIndexOutOfBoundsException e){} 
    }
	
	protected void initUnchoke(){
		for (int i=0; i<4; i++){
			unchoked.add(i, null);
		}
	}
	
	protected ArrayList<Integer> copyInt(ArrayList<Integer> c){
		return new ArrayList<Integer> (c);
	}
	
	protected ArrayList<DTNHost> copyHost(ArrayList<DTNHost> h){
		return new ArrayList<DTNHost> (h);
	}
	
}

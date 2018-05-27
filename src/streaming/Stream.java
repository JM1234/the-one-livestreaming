package streaming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.TreeMap;

/**
 * Works like a MessageListener, only with a stream of chunks.
 * Handles generation of chunks.
 * @author janz
 *
 */
public class Stream {
	
	private static int durationPerChunk;
	private static double byterate;
	private static int prebuffer;
	
	private String streamID;
	private int chunkNo=0000;
	public boolean isStreaming = true;

	private TreeMap<Integer, StreamChunk> streamSet;
	private ArrayList<Integer> buffermap;
	private StreamChunk latestChunk;
	
	protected String id;
	private double timeStarted=-1;
	private int playing=-1; //index currently playing
	private int ack=-1; //last consecutive sent. == windowStart
	private int windowSize; //10
	private int chunkStart=-1;
	
	//variable for limitTime. randomize variable on how int the live stream is gonna last
	public Stream(String streamID, int durationPerChunk, double byterate, double timeStarted, int windowSize) {
		Stream.byterate = byterate;
		Stream.durationPerChunk = durationPerChunk;
		
		this.streamID = streamID;
		this.timeStarted = timeStarted;
		this.windowSize = windowSize;
		
		streamSet = new TreeMap<Integer,StreamChunk>();
		buffermap = new ArrayList<Integer>();
	}

	public void addChunk(StreamChunk chunk){
		streamSet.put(chunk.getChunkID(), chunk);
		buffermap.add(chunk.getChunkID());
	}

	public double getTimeStarted(){
		return timeStarted;
	}
	
	public void startLiveStream(){
		isStreaming = true;
	}

	public void stopStream(){
		streamSet.clear();
		isStreaming=false;
	}
	
	public StreamChunk getLatestChunk(){
		return latestChunk;
	}
	
	public void generateChunks(String fileID, int fID){
		//create chunks
		int chunkID = generateChunkID(fileID, chunkNo++);
		StreamChunk chunk = new StreamChunk(id, chunkID);
		chunk.setFragmentIndex(fID);
		streamSet.put(chunkID, chunk);
		latestChunk = chunk; 
		ack = chunkID;
		buffermap.add(chunk.getChunkID());
	}

	private int generateChunkID(String fileID, int chunkNo){
		int chunkID = chunkNo;
		return chunkID;
	}
	
	public StreamChunk getChunk(double time){
	////within boundary	
		for(int key : streamSet.keySet()){
			StreamChunk chunk = streamSet.get(key);
			
			double stime = chunk.getCreationTime();
			if ((stime<=time) && time<stime+durationPerChunk)
				return chunk;
		}
		return null;
	}
	
	public StreamChunk getChunk(int chunkID){
		return streamSet.get(chunkID);
	}

	public Collection<StreamChunk> getChunks(){
		return streamSet.values();
	}
	
	public ArrayList<Integer> getBuffermap(){
		return buffermap;
	}
	
	public double getByterate(){
		return byterate;
	}
	
	public int getDurationPerChunk(){
		return durationPerChunk;
	}

	
	/*
	 * Watcher functions
	 */
	public boolean isBufferReady(int id){
		int ctr=0;
		
		for (int toPlay = id+1; toPlay<=streamSet.lastKey() && ctr< prebuffer/2 ; toPlay++, ctr++){
			if (!streamSet.containsKey(toPlay)){
				break;
			}
		}
		return (ctr==(prebuffer/2)? true:false);
	}
	
	public void playNext(){
		playing = playing+1;
	}
	
	public int getPlaying(){
		return playing;
	}
	
	public int getNext(){
		return playing+1;
	}
	
	public boolean isReady(int i){
		try{
			if(streamSet.get(i) !=null){
				return true;
			}
		}
		catch(IndexOutOfBoundsException e){}
		return false;
	}
	
	public void setAck(int curr){
		if (curr-ack == 1 || curr == 0){
			ack=curr;
		}
		while (streamSet.containsKey(ack+1)){
			ack++;
		}
	}
	
	public int getAck(){
		return ack;
	}
	
	public void skipNext(){
		playing++;
	}
	
	public int getWindowSize(){
		return this.windowSize;
	}
}


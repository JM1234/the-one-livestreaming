package streaming;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.NoSuchElementException;

import core.DTNHost;
import core.SimClock;

public class NodeProperties {

	private double timeBroadcastReceived=-1;
	private double timeStartedPlaying=-1;
	private double timeLastPlayed=-1;
	private double timeFirstRequested=-1;
	private double timeFirstChunkReceived=-1;
	private int lastChunkPlayed=-1;
//	private double nrofTimesInterrupted=0;
//	private int nrofDuplicateChunks=0;
//	private int nrofDuplicateRequest=0;
	private int nrofTimesRequested=0;
	private int nrofTimesSentIndex=0;
	private int nrofTimesSentTrans=0;
	private int nrofTimesSentChunk=0;
	private int nrofFragmentsCreated=0;
	
	private TreeMap<Integer, Double> chunksReceived= new TreeMap<Integer, Double>();
//	private HashMap<Double, ArrayList<DTNHost>> unchoked = new HashMap<Double, ArrayList<DTNHost>>();
	private TreeMap<Integer, Double> chunkWaitTime = new TreeMap<Integer, Double>();
	private HashMap<Integer, Double> chunksSkipped = new HashMap<Integer, Double>();
	private	HashMap<Integer, Double> requested = new HashMap<Integer, Double>();
	private ArrayList<Integer> duplicateRequest = new ArrayList<Integer>();
	private ArrayList<Integer> duplicateChunks = new ArrayList<Integer>();
	private HashMap<Integer, Double> interruptions = new HashMap<Integer, Double>(); //time naglapse until umabot na ini na node
	private HashMap<Integer, Double> holdInterrupt = new HashMap<Integer, Double>(); //time nag start an interrupt
	
	private int ack;
	private int sizeAdjustedCount=0;
	
	public void addChunk(int chunk){
		chunksReceived.put(chunk, SimClock.getTime());
		double waitTime = chunksReceived.get(chunk) - requested.get(chunk);
		chunkWaitTime.put(chunk, waitTime);
	}
	
	public double getAverageWaitTime(){
		if (chunkWaitTime.isEmpty()) return -1;

		double average = 0;
		for(int id : chunkWaitTime.keySet()){
			average +=chunkWaitTime.get(id);
		}
		average/=chunkWaitTime.size();
		return average;
	}
	
	public TreeMap<Integer, Double> getChunkWaitTime(){
		return chunkWaitTime;
	}
	
	public HashMap<Integer, Double> getChunksSkipped(){
		return chunksSkipped;
	}
	
	public void setTimeBroadcastReceived(double timeBroadcastReceived){
		this.timeBroadcastReceived = timeBroadcastReceived;
	}
	 
	public void setTimeStartedPlaying(double timeStartedPlaying){
		this.timeStartedPlaying = timeStartedPlaying;
	}
	
	public void setTimeLastPlayed(double timeLastPlayed, int id){
		this.timeLastPlayed = timeLastPlayed;
		this.lastChunkPlayed = id;
	}
	
	public int getLastPlayedChunk(){
		return lastChunkPlayed;
	}
	
	public void setTimeFirstRequested(double timeFirstRequested){
		this.timeFirstRequested=timeFirstRequested;
	}

	public void setTimeFirstChunkReceived(double timeFirstChunkReceived){
		this.timeFirstChunkReceived=timeFirstChunkReceived;
	}

	public void setNrofTimesRequested(int nrofTimesRequested){
		this.nrofTimesRequested = nrofTimesRequested;
	}
	
	public void addDuplicateChunk(int id){
		this.duplicateChunks.add(id);
//		this.nrofDuplicateChunks++;
	}

//	public void setNrofDuplicateRequest(int nrofDuplicateRequest){
//		this.nrofDuplicateRequest=nrofDuplicateRequest;
//	}
	
//	public void setNrofTimesInterrupted(double nrofTimesInterrupted){
//		this.nrofTimesInterrupted=nrofTimesInterrupted;
//	}

	public void addInterruption(int chunkId, double time){
		holdInterrupt.put(chunkId, time);
	}
	
	public void addResumed(int chunkId, double time){
		interruptions.put(chunkId, time);
	}
	
	public double getStored(int chunkId){
		return holdInterrupt.get(chunkId);
	}
	
	public HashMap<Integer, Double> getInterruptions(){
		return interruptions;
	}
	
	public double getTimeBroadcastReceived(){
		return timeBroadcastReceived;
	}
	 
	public double getTimeStartedPlaying(){
		return timeStartedPlaying;
	}
	
	public double getTimeLastPlayed(){
		return timeLastPlayed;
	}
	
	public double getTimeFirstRequested(){
		return timeFirstRequested;
	}
	
	public double getTimeFirstChunkReceived(){
		return timeFirstChunkReceived;
	}
	
	public int getNrofTimesRequested(){
		return nrofTimesRequested;
	}
	
	public int getNrofDuplicateChunks(){
		return duplicateChunks.size(); //nrofDuplicateChunks;
	}

	public int getNrofDuplicateRequest(){
		return duplicateRequest.size(); //nrofDuplicateRequest;
	}
	
	public ArrayList<Integer> getDuplicateChunks(){
		return duplicateChunks;
	}
	
	public ArrayList<Integer> getDuplicateRequest(){
		return duplicateRequest;
	}

	public int getNrofTimesInterrupted(){
		return holdInterrupt.size();
//		return //interruptions.size();
	}
	
	public TreeMap<Integer, Double> getChunksReceived(){
		return chunksReceived;
	}	

	public int getNrofChunksReceived(){
		return chunksReceived.size();
	}
	
//	public void updateUnchoke(double curTime, ArrayList<DTNHost> hosts){
//		unchoked.put(curTime, hosts);
//	}
	
//	public void updateAvailable(double curTime, ArrayList<DTNHost> hosts){
//		availableH.put(curTime, hosts);
//	}
//	
//	public HashMap<Double, ArrayList<DTNHost>> getUnchokeList(){
//		return unchoked;
//	}
//	
//	public HashMap<Double, ArrayList<DTNHost>> getAvailableList(){
//		return availableH;
//	}
	
//	public HashMap<Integer, Double> getRequested(){
//		return requested;
//	}

	public void addRequested(int newId){
//		for(int newId: newIds){
		if (requested.containsKey(newId)){
//			nrofDuplicateRequest++;
			duplicateRequest.add(newId);
		}
		else{
			requested.put(newId, SimClock.getTime()); //put the first time this was requested
		}
//		}
	}
	
	public void setAck(int ack){
		this.ack = ack;
	}
	
	public int getAck(){
		return ack;
	}
	
	public void setSizeAdjustedCount(int sizeAdjustedCount){
		this.sizeAdjustedCount = sizeAdjustedCount;
	}
	
	public int getSizeAdjustedCount(){
		return sizeAdjustedCount;
	}
	
	public void incNrOfTimesSentIndex(){
		nrofTimesSentIndex++;
	}
	
	public void incNrOfTimesSentTrans(){
		nrofTimesSentTrans++;
	}
	
	public void incNrOfTimesSentChunk(){
		nrofTimesSentChunk++;
	}

	public void incNrOfFragmentsCreated(){
		nrofFragmentsCreated++;
	}
	
	public int getNrOfTimesSentIndex(){
		return nrofTimesSentIndex;
	}
	
	public int getNrofTimesSentTrans(){
		return nrofTimesSentTrans;
	}
	
	public int getNrOfTimesSentChunk(){
		return nrofTimesSentChunk;
	}
	
	public int getNrOfFragmentsCreated(){
		return nrofFragmentsCreated;
	}
	
	public int getLastChunkReceived(){
		try{
			return chunksReceived.lastKey();
		}catch(NoSuchElementException e){
			return -1;
		}
	}

	public void addSkippedChunk(int id){
		chunksSkipped.put(id, SimClock.getTime());
	}
	
	public int getNrOfSkippedChunks(){
		return chunksSkipped.size();
	}
	
	public double getAveLengthOfInterruption(){
		if (interruptions.size()==0) return 0;
		
		double ave=0;
		for (double lapse : interruptions.values()){
			ave+=lapse;
		}
		ave/=interruptions.size();
		return ave;
	}
	
	public void finalizeInterruption(){
		for (int c: holdInterrupt.keySet()){
			if (!interruptions.containsKey(c)){
				interruptions.put(c, SimClock.getTime()-holdInterrupt.get(c));
			}
		}
	}
}

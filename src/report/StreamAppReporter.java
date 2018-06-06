package report;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import applications.StreamingApplication;
import core.Application;
import core.ApplicationListener;
import core.DTNHost;
import core.Settings;
import core.SimClock;
import jxl.read.biff.BiffException;
import jxl.write.WriteException;
import movement.MovementModel;
import streaming.NodeProperties;
import writer.WriteExcel;

/*
 * report for StreamApplication
 */
public class StreamAppReporter extends Report implements ApplicationListener{

	public static final String STARTED_PLAYING = "startedPlaying";
	public static final String LAST_PLAYED = "lastPlayed";
	public static final String INTERRUPTED = "interrupted";
	public static final String RESUMED = "resumed";
	public static final String RECEIVED_CHUNK = "receivedChunk";
	public static final String RECEIVED_DUPLICATE = "receivedDuplicate";
	public static final String FIRST_TIME_REQUESTED = "firstTimeRequest";
	public static final String FIRST_TIME_RECEIVED = "firstTimeReceived";
	public static final String BROADCAST_RECEIVED = "broadcastReceived";
	public static final String RESENT_REQUEST = "resentRequest";
	public static final String SENT_REQUEST = "sentRequest";
	public static final String SENT_BUNDLE_REQUEST = "sentBundleRequest";
	public static final String UNCHOKED = "unchoked";
	public static final String INTERESTED = "interested";
	public static final String CHUNK_CREATED = "chunkCreated";
	public static final String UPDATE_AVAILABLE_NEIGHBORS = "updateAvailableNeighbor";
	public static final String UPDATE_ACK = "updateAck";
	public static final String SIZE_ADJUSTED = "sizeAdjusted";
	public static final String SENT_INDEX_FRAGMENT = "sentIndexFragment";
	public static final String SENT_TRANS_FRAGMENT = "sentTransFragment";
	public static final String SENT_CHUNK = "sentChunk";
	public static final String FRAGMENT_CREATED = "fragmentCreated";
	public static final String SKIPPED_CHUNK = "skippedChunk";
	
	private static String excelDir = "";
	
	private static TreeMap<DTNHost, NodeProperties> nodeRecord = new TreeMap<DTNHost, NodeProperties>();
	private int createdChunks=0;
	
	public void gotEvent(String event, Object params, Application app, DTNHost host) {
	
		if (!(app instanceof StreamingApplication)) return;
		
		NodeProperties nodeProps = nodeRecord.get(host);
		if (nodeProps == null){
			nodeRecord.put(host, new NodeProperties());
			nodeProps = nodeRecord.get(host);
		}
		if (event.equalsIgnoreCase(BROADCAST_RECEIVED)){
			double time=(double) params;
			nodeProps.setTimeBroadcastReceived(time);
		}
		else if (event.equalsIgnoreCase(CHUNK_CREATED)){
			createdChunks++;
		}
		else if (event.equalsIgnoreCase(LAST_PLAYED)){
			int id= (int) params;
			if (nodeProps.getTimeLastPlayed() == -1){
				nodeProps.setTimeStartedPlaying(SimClock.getTime());
			}
			nodeProps.setTimeLastPlayed(SimClock.getTime(), id);
		}
		else if (event.equalsIgnoreCase(INTERRUPTED)){
			int chunkId = (int) params;
			nodeProps.addInterruption(chunkId, SimClock.getTime());
		}
		else if (event.equalsIgnoreCase(RESUMED)){
			int chunkId = (int) params;
			double lapse = SimClock.getTime() - nodeProps.getStored(chunkId);
			nodeProps.addResumed(chunkId, lapse);
		}
		else if (event.equalsIgnoreCase(RECEIVED_CHUNK)){
			int id = (int) params;
			nodeProps.addChunk(id);
		}
		else if (event.equalsIgnoreCase(RECEIVED_DUPLICATE)){
//			nodeProps.incNrOfDuplicateChunks();
			int id = (int) params;
			nodeProps.addDuplicateChunk(id);
	
		}
		else if (event.equalsIgnoreCase(FIRST_TIME_REQUESTED)){
			double time = (double) params;
			nodeProps.setTimeFirstRequested(time);
		}
		else if (event.equalsIgnoreCase(FIRST_TIME_RECEIVED)){
			double time = (double) params;
			nodeProps.setTimeFirstChunkReceived(time);
		}
		else if (event.equalsIgnoreCase(SENT_REQUEST)){
			int ctr= nodeProps.getNrofTimesRequested()+1;
			int id = (int) params;
			nodeProps.addRequested(id);
			nodeProps.setNrofTimesRequested(ctr);
		}
		
		else if (event.equalsIgnoreCase(SENT_BUNDLE_REQUEST)){
			ArrayList<Integer> received = (ArrayList<Integer>) params;
			for(int c:received){
				nodeProps.addRequested(c);
			}
		}
//		else if (event.equalsIgnoreCase(UNCHOKED)){
//			ArrayList<DTNHost> unchokedH = (ArrayList<DTNHost>) params;
//			nodeProps.updateUnchoke(SimClock.getTime(), unchokedH);
//		}
//		else if (event.equalsIgnoreCase(INTERESTED)){
//			ArrayList<DTNHost> interestedH = (ArrayList<DTNHost>) params;
//			nodeProps.updateInterested(SimClock.getTime(), interestedH);
//		}
//		else if (event.equals(UPDATE_AVAILABLE_NEIGHBORS)){
//			ArrayList<DTNHost> availableH = (ArrayList<DTNHost>) params;
//			nodeProps.updateAvailable(SimClock.getTime(), availableH);
//		}
		else if (event.equals(UPDATE_ACK)){
			int ack = (int) params;
			nodeProps.setAck(ack);
		}
		else if (event.equalsIgnoreCase(SIZE_ADJUSTED)){
			nodeProps.setSizeAdjustedCount(nodeProps.getSizeAdjustedCount()+1);
		}
		else if (event.equalsIgnoreCase(SENT_INDEX_FRAGMENT)){
			nodeProps.incNrOfTimesSentIndex();
		}
		else if (event.equalsIgnoreCase(SENT_TRANS_FRAGMENT)){
			nodeProps.incNrOfTimesSentTrans();
		}
		else if (event.equalsIgnoreCase(SENT_CHUNK)){
			nodeProps.incNrOfTimesSentChunk();
		}
		else if (event.equalsIgnoreCase(FRAGMENT_CREATED)){
			nodeProps.incNrOfFragmentsCreated();
		}
//		else if (event.equalsIgnoreCase(SKIPPED_CHUNK)){
//			int id = (int) params;
//			nodeProps.addSkippedChunk(id);
//		}
		nodeRecord.put(host, nodeProps);
		
	}

	public void recordPerNode(){
		String eol = System.getProperty("line.separator");
		String chunkRecord="";

		String chunksCreated = "Total Chunks Created: " + createdChunks;
		write(chunksCreated);
//		
		for (DTNHost h: nodeRecord.keySet()){
				chunkRecord= " --------" + h + "---------->" + eol
				 + "chunks_received: " + nodeRecord.get(h).getChunksReceived() + eol
//				 + "unchoked_hosts: " + nodeRecord.get(h).getUnchokeList() + eol
//				 + "available_hosts: " + nodeRecord.get(h).getAvailableList() + eol
				 + "chunk_wait_time: " + nodeRecord.get(h).getChunkWaitTime().values() + eol
				 + "duplicate _requests: " + nodeRecord.get(h).getDuplicateRequest() + eol
				 + "duplicate_chunks: " + nodeRecord.get(h).getDuplicateChunks() + eol
				 + "interrupted_times: " + nodeRecord.get(h).getInterruptions();
//				 + "chunks_skipped: " + nodeRecord.get(h).getChunksSkipped();
//				 + "unchoked list: " + nodeRecord.get(h).getUnchokeList();
////				chunkRecord = String.format("%8s %s %8s %s %5s %s %4s %s %4s %s %4s %8s %s %8s %s %8s %s %4s %s %4s %s %4s %s %4s %s %4s %s %4s", 
////						timeStartedPlaying, ' ', timeLastPlayed, ' ', ack, ' ', numberOfTimesInterrupted,' ',  numberOfChunksReceived,' ',
////						numberOfDuplicateChunksReceived, ' ',averageWaitTime, ' ',timeFirstRequested, ' ',timeFirstChunkReceived, ' ', 
////						numberOfTimesRequested, ' ', numberOfChunksRequestedAgain, ' ', numberOfTimesAdjusted,' ', totalIndexFragmentSent, ' ',
////						totalTransFragmentSent,' ', totalChunksSent );
////				
//				chunkRecord = String.format("%3s%s %8s %8s %5s %4s %4s %4s %8s %8s %8s %4s %4s %4s %4s %4s %4s %4s", 
//						h, ":" , timeStartedPlaying, timeLastPlayed, ack, numberOfTimesInterrupted,numberOfChunksReceived,
//						numberOfDuplicateChunksReceived, averageWaitTime, timeFirstRequested, timeFirstChunkReceived,
//						numberOfTimesRequested, numberOfChunksRequestedAgain,  numberOfTimesAdjusted,totalIndexFragmentSent,
//						totalTransFragmentSent,totalChunksSent, nrOfFragmentsCreated );
				
				write(chunkRecord);
		}
	
	}
	
	public int getSeed(){
		Settings s = new Settings(MovementModel.MOVEMENT_MODEL_NS);
		return s.getInt(MovementModel.RNG_SEED);
	}
	
	public String getReportDir(){
		Settings s = new Settings();
		return s.getSetting(REPORTDIR_SETTING);
	}
	
	public void done(){
		WriteExcel test = new WriteExcel();
	
		//excelDir = "/home/jejejanz/janeil_workspace/the-one-livestreaming/" + getReportDir() + getScenarioName() + ".xls";
		excelDir = "C:/Users/janz/git/the-one-livestreaming/" + getReportDir() + getScenarioName() + ".xls";
		
		System.out.println(" excelDir: " + excelDir);
		test.setOutputFile(excelDir);
	
		try {
			test.init();
			test.write(nodeRecord, getSeed());
			test.writeToFile();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (WriteException e) {
			e.printStackTrace();
		} catch (BiffException e) {
			e.printStackTrace();
		}
		
		recordPerNode();
		super.done();
		nodeRecord.clear();
	}
	
	public double round(double value) {
		return (double)Math.round(value * 100)/100;
	}
	
	
}

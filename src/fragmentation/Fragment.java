package fragmentation;

import java.util.ArrayList;
import java.util.List;

import javax.swing.plaf.synth.SynthScrollBarUI;

import core.SimClock;
import streaming.Stream;
import streaming.StreamChunk;

public class Fragment {
	private int id; //index id
	private ArrayList<Integer> bChunks;
	private double timeCreated;
	private String sourceId;
	private int startPosition=-1; //starting chunk id of the fragment
	private int endPosition=-1;
	private int size; //in bytes
	private boolean isComplete = false;
	
	public Fragment(int id, List<Integer> bChunks){
		this.id = id;
		this.bChunks = new ArrayList<Integer> (bChunks);
		timeCreated = SimClock.getTime();
	}
	
	public double getTimeCreated(){
		return timeCreated;
	}
	
	public ArrayList<Integer> getBundled(){
		return bChunks;
	}
	
	public int getId(){
		return id;
	}
	
	public int getFirstChunkID(){
		return bChunks.get(0);
	}
	
	public int startPosition(){
		return startPosition;
	}
	
	public void setStartPosition(int startPosition){
		this.startPosition = startPosition;
	}
	
	public void setEndPosition(int endPosition){
		this.endPosition = endPosition;
	}
	
	public int getStartPosition(){
		return startPosition;
	}
	
	public int getEndPosition(){
		return endPosition;
	}
	
	public boolean isComplete(){
		return isComplete;
	}
	
	public int getEndChunk(){
		return bChunks.get(bChunks.size()-1);
	}
	
//	public double getSize(){
//		return * getNoOfChunks());
//	}
	
	public int getNoOfChunks(){
		return bChunks.size();
	}
	
	public void updateBundle(int pos, int c){ //mainly used by watcher. adding transmission level frags
		bChunks.set(pos, c);
		
		if (pos < startPosition || startPosition==-1){
			startPosition = pos;
		}
		else if (pos>endPosition || endPosition==-1){
			endPosition = pos;
		}
		
		checkIfIndexComplete();
	}
	
	private void checkIfIndexComplete(){
		for (Integer c: bChunks){
			if (c==null){
				return;
			}
		}
		isComplete = true;
	}
	
	public void setIndexComplete(){
		isComplete = true;
	}
	
	public int indexOf(int id){
		return bChunks.indexOf(id);
//		for(int i=0; i<bChunks.size(); i++){
//			if (bChunks.get(i) == id){
//				return i;
//			}
//		}
//		return -1;
	}
	
}

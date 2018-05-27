package streaming;

import core.SimClock;

public class StreamChunk{

	//bitrate in kB
	public static final double m240p = 43.75; //350kbps
	public static final double m360p = 87.5; //700kbps
	public static final double m480p = 150; //1200kbps
	public static final double m720p = 312.5; //2500kbps
	public static final double m1080p = 625; //5000kbps
	public static final int CHUNK_TTL = 0; //infinite
	
	private String fileID;
	private int chunkID;
	
	private int fId=0;
	private double timeCreated; //+30 for end time?
	
	public StreamChunk(String fileID, int chunkID){
		this.fileID = fileID;
		this.chunkID = chunkID;
		timeCreated = SimClock.getTime();
	}
	
	public String getFileID(){
		return fileID;
	}
	
	public int getChunkID(){
		return chunkID;
	}
	
	public double getCreationTime(){
		return timeCreated;
	}
	
	public void setFragmentIndex(int fId){
		this.fId = fId;
	}
	
	public int getFragmentIndex(){
		return fId;
	}

}

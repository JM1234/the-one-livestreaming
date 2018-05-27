package report;

import java.util.ArrayList;
import java.util.HashMap;


import applications.StreamingApplication;
import core.Application;
import core.ApplicationListener;
import core.DTNHost;
import core.Message;
import streaming.NodeProperties;

public class RequestListReport extends Report implements ApplicationListener{

	private HashMap<DTNHost, NodeProperties> nodeRecord = new HashMap<DTNHost, NodeProperties>();
	
	private DTNHost currHost;

	@Override
	public void gotEvent(String event, Object params, Application app, DTNHost host) {
		if (!(app instanceof StreamingApplication)) return;
	
//		Message m = (Message) params;
//		DTNHost from = m.getFrom();
//		ArrayList<Long> chunkUpdate = (ArrayList<Long>) m.getProperty("buffermap");
//		ArrayList<Long> temp = availableHosts.get(from);
//		temp.addAll(chunkUpdate);
	
		
		NodeProperties nodeProps = nodeRecord.get(host);
		
		if (nodeProps == null){
			nodeRecord.put(host, new NodeProperties());
			nodeProps = nodeRecord.get(host);
		}
		
		if (event.equalsIgnoreCase("HOSTNAME")){
			DTNHost from = (DTNHost) params;
			this.currHost = from;
		}
		else if (event.equalsIgnoreCase("UPDATE")){
			ArrayList<Long> update = (ArrayList<Long>) params;
//			nodeProps.toSearch.put(currHost, update);
			this.currHost = null;
		}
		nodeRecord.put(host, nodeProps);
	}

	public void done(){
		String eol = System.getProperty("line.separator");
	
		String line= " ";
		for (DTNHost h: nodeRecord.keySet()){
	
			line = h + " : " + eol;
//			for (DTNHost currH : nodeRecord.get(h).toSearch.keySet()){
//				line+= "      " + currH + " : " + nodeRecord.get(h).toSearch.get(currH) + eol;
//			}
//			System.out.println("LINE: "+ line);
			write(line);
		}
		super.done();

	}
}

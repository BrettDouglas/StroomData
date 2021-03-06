package me.doubledutch.stroom.filters;

import org.apache.log4j.Logger;

import me.doubledutch.stroom.perf.*;
import me.doubledutch.stroom.streams.*;
import me.doubledutch.stroom.*;
import me.doubledutch.lazyjson.*;
import me.doubledutch.stroom.client.StreamConnection;
import java.util.*;
import org.json.*;
import java.net.*;
import javax.script.*;

public class FilterService extends Service{
	private final Logger log = Logger.getLogger("Filter");

	private long outputIndex=-1;
	private double sampleRate=1.0;
	private BatchMetric metric=null;
	private List<String> buffer=null;
	private int bufferSize=0;
	private long lastFlush=0;

	public FilterService(StreamHandler handler,JSONObject obj) throws Exception{
		super(handler,obj);
		
		String strType=obj.getString("type");
		if(strType.equals("sample")){
			type=SAMPLE;
			sampleRate=obj.getDouble("sample_rate");
		}
	}

	private synchronized void loadState() throws Exception{
		if(getStream("state").getCount()>0){
			JSONObject obj=new JSONObject(getStream("state").getLast());
			index=obj.getLong("i");
			outputIndex=obj.getLong("o");
		}
	}

	private synchronized void saveState() throws Exception{
		if(index>-1){
			JSONObject obj=new JSONObject();
			obj.put("i",index);
			obj.put("o",outputIndex);
			getStream("state").append(obj,StreamConnection.FLUSH);
		}
	}

	public synchronized void reset() throws Exception{
		getStream("state").truncate(0);
		getStream("output").truncate(0);
		index=-1;
		outputIndex=-1;
	}

	private String processDocument(String str) throws ServiceException{
		String out=null;
		if(type==HTTP){
			metric.startTimer("http.request");
			Map<String,String> headers=new HashMap<String,String>();
			headers.put("X-Stroom-Service",getId());
			headers.put("X-Stroom-Index",""+getIndex());
			out=Utility.postURL(url,str,headers);	
			metric.stopTimer("http.request");	
		}else if(type==JAVASCRIPT){
			try{
				metric.startTimer("javascript.deserialize");
				jsEngine.put("raw",str);
				jsEngine.eval("var obj=JSON.parse(raw);");
				metric.stopTimer("javascript.deserialize");

				// jsEngine.eval("var obj="+str+";");
				metric.startTimer("javascript.run");
				jsEngine.eval("var result=map(obj);");
				metric.stopTimer("javascript.run");
				metric.startTimer("javascript.serialize");
				jsEngine.eval("if(result!=null)result=JSON.stringify(result);");
				Object obj=jsEngine.get("result");
				metric.stopTimer("javascript.serialize");

				if(obj!=null){
					out=(String)obj;
				}else{
					out="";
				}
			}catch(ScriptException se){
				se.printStackTrace();
				setLastError(se.toString());
				throw new ServiceException("Failed to execute script");
			}
		}else if(type==QUERY){

		}else if(type==SAMPLE){
			if(Math.random()<sampleRate){
				out=str;
			}else{
				out="";
			}
		}
		if(out==null){
			// Assume error
			throw new ServiceException("Could not process document");
		}
		return out;
	}

	private void flushOutput() throws Exception{
		metric.startTimer("output.append");
		if(buffer.size()>0){
			List<Long> result=getStream("output").append(buffer);
			outputIndex=result.get(result.size()-1);
		}
		metric.stopTimer("output.append");
		// TODO: add selective state saving point
		metric.startTimer("state.append");
		saveState();
		metric.stopTimer("state.append");
		buffer.clear();
		bufferSize=0;
		lastFlush=System.currentTimeMillis();
	}

	public void run(){
		try{
			loadState();
			lastFlush=System.currentTimeMillis();
			buffer=new ArrayList<String>(getBatchSize()*2);
			log.info(getId()+" restarting at "+(index+1));
			isRunning(true);
			int groupBatch=0;
			while(shouldBeRunning()){
				// Load
				if(buffer.size()==0){
					metric=new BatchMetric();
					metric.startTimer("batch.time");
					groupBatch=0;
				}
				metric.startTimer("input.get");
				List<String> batch=getStream("input").get(index+1,index+getBatchSize()+1);
				metric.stopTimer("input.get");
				groupBatch+=batch.size();
				// Process
				if(batch.size()==0){
					// No new data, wait before pulling again
					try{
						Thread.sleep(getWaitTime());
					}catch(Exception se){}
				}else{
					// List<String> output=new ArrayList<String>();
					int retryIndex=0;
					for(int i=retryIndex;i<batch.size();i++){
						String str=batch.get(i);
						try{
							String out=processDocument(str);
							if(out!=null && out.length()>0){
								if(out.startsWith("[")){
									// LazyParser parser=new me.doubledutch.stroom.jsonjit.JSONParser(out);
									LazyArray array=new LazyArray(out);
									for(int n=0;n<array.length();n++){
										LazyObject jobj=array.getJSONObject(n);
										String jobjString=jobj.toString();
										buffer.add(jobjString);
										bufferSize+=jobjString.length();
									}
								}else{
									bufferSize+=out.length();
									buffer.add(out);
								}
							}
							index++;
						}catch(ServiceException se){
							if(getErrorStrategy()==Service.ERR_IGNORE){
								// Just keep processing
							}else if(getErrorStrategy()==Service.ERR_RETRY){
								try{
									Thread.sleep(2000);
								}catch(Exception ee){}
								i--; // rewind position
							}else if(getErrorStrategy()==Service.ERR_HALT){
								flushOutput();
								shouldBeRunning(false);
								isRunning(false);
								return;
							}
						}
					}
					
				}
				if(buffer.size()>getBatchSize() || (System.currentTimeMillis()-lastFlush)>getBatchTimeout() || bufferSize>256*1024){
					flushOutput();
				}
				if(buffer.size()==0){
					metric.setSamples(groupBatch);
					metric.stopTimer("batch.time");
					addBatchMetric(metric);
				}
			}
		}catch(LazyException le){
			le.printStackTrace();
		}catch(Exception e){
			e.printStackTrace();
		}
		try{
			saveState();
		}catch(Exception e){
			e.printStackTrace();
		}
		isRunning(false);
	}

	public JSONObject toJSON() throws JSONException{
		JSONObject obj=super.toJSON();
		return obj;
	}
}
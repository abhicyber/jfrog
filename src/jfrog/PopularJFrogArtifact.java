package jfrog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.JSONArray;
import org.json.JSONObject;

public class PopularJFrogArtifact {
	
	//Private Fields
	private static TreeMap<Integer, List<String>> statsMap = new TreeMap<>(Collections.reverseOrder()); 
	
	final private static String jfrogAPIURL = "http://34.68.71.97/artifactory/api";
	final private static String aqlURI = "/search/aql/";
	final private static String storageURI = "/storage/";
	private static HttpURLConnection conn;
	
	//Callable class for /stats GET calls - MultiThread
	private static class StatsThread implements Callable {
		private static Object lock = new Object();
		public String pathName;
		
		public StatsThread(String pathName) {
			this.pathName = pathName;
		}
		
		@Override
		public SimpleEntry<Integer,String> call() throws Exception {
			StringBuilder sb = new StringBuilder();
            String output;
            JSONObject statObj=null;
	        synchronized (lock) {
	        	conn = getConnection(storageURI+pathName+"?stats", "GET");
	        }
	        BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
	        while ((output = br.readLine()) != null) {
	                sb.append(output + "\n");
	        }
	        synchronized (lock) {
	        statObj = new JSONObject(sb.toString());
	        	return new AbstractMap.SimpleEntry<Integer, String>(statObj.getInt("downloadCount"), pathName);
	        }
		}
		
	}
	//Set Corporate Proxy
	static {
		System.setProperty("http.proxyHost","www-proxy-hqdc.us.oracle.com");
		System.setProperty("http.proxyPort","80");
	}
			
	public static void getArtifacts(String repoName) throws Exception {
		try {
			
			//Multi Thread
			ExecutorService service = Executors.newCachedThreadPool();

			//Single Thread
			//ExecutorService service = Executors.newFixedThreadPool(1);

			
			conn = getConnection(aqlURI, "POST");
			String json = "items.find({\"repo\":{\"$eq\":\"" + repoName + "\"}})";
			StringBuilder sb = new StringBuilder();
            String output;
            
            //Write Body
			OutputStream os = conn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.close();
            
            BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));

            while ((output = br.readLine()) != null) {
                sb.append(output + "\n");
            }
            JSONObject repoObj = new JSONObject(sb.toString());
            
            JSONArray resultsArray = (JSONArray) repoObj.get("results");

            Iterator<Object> iterator =  resultsArray.iterator();
            List<Future> futures = new ArrayList<Future>();
            
            //Use MultiThreading to invoke /GET call for all paths
            while (iterator.hasNext()) {
            	sb = new StringBuilder();
            	JSONObject obj = (JSONObject) iterator.next();
            	String pathName=pathName(repoName, obj);
            	
            	Future<SimpleEntry> future = service.submit(new StatsThread(pathName));
            	futures.add(future);
            }
            //Shutdown the service
            service.shutdown();
            
            for (Future<SimpleEntry<Integer,String>> future : futures) {
            	if(statsMap.containsKey(future.get().getKey())) {
            		statsMap.get(future.get().getKey()).add(future.get().getValue());
            			}else {
            		statsMap.put(future.get().getKey(), new ArrayList<String>() { 
            			{ 
            				add(future.get().getValue()); 
            			} 
            			});
            		}
            	}
             analyze(repoName, statsMap);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}finally {
            conn.disconnect();
		}

	}


	//Main Method
	public static void main(String[] args) throws Exception {
		
		if (args.length > 0) {
			Instant start = Instant.now();
						
			getArtifacts(args[0]);
			//getArtifacts("jcenter-cache");
			//getArtifacts("gradle-release-local");
			
			Instant end = Instant.now();
			Duration timeElapsed = Duration.between(start, end);
			System.out.println("Time taken: "+ timeElapsed.toMillis() +" milliseconds");
		}else {
			System.err.println("Please pass the repo name, e.g: jfrog.PopularJFrogArtifact jcenter-cache");
			System.exit(1);
		}

	}

	//Helper Methods
	private static void analyze(String repoName, Map<Integer, List<String>> statsMap) {
		if (!statsMap.isEmpty()) {
			System.out.println("Repo Name: "+repoName);
			System.out.println("Most Popular Artifact(s) Download Count is: "+statsMap.keySet().toArray()[0]);
			System.out.println(
					String.format("Total No of Artifact(s) is/are %d; Artifact(s) Name(s) are: %.80s ...",statsMap.get(statsMap.keySet().toArray()[0]).size(), statsMap.get(statsMap.keySet().toArray()[0]))
						);
			System.out.println("Second Most Popular Artifact(s) Download Count is: "+statsMap.keySet().toArray()[1]);
			System.out.println(
					String.format("Total No of Artifact(s) is/are %d; Artifact(s) Name(s) are: %.80s ...",statsMap.get(statsMap.keySet().toArray()[1]).size(), statsMap.get(statsMap.keySet().toArray()[1]))
						);
		}
	}

	private static HttpURLConnection getConnection(String uri, String method) throws IOException {
		URL url = new URL(jfrogAPIURL+uri);
		conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(method);
		conn.setRequestProperty("Accept", "application/json");
		conn.setRequestProperty("Content-Type", "text/plain");
		conn.setRequestProperty("Authorization", "Basic YWRtaW46RnFyc09TeWkxOA==");
		conn.setDoOutput(true);
		conn.setDoInput(true);
		return conn;
	}
	
	private static String pathName(String repoName, JSONObject obj) {
		return repoName+"/"+obj.get("path")+"/"+obj.get("name");
	}
}

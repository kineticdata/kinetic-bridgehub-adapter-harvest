package com.kineticdata.bridgehub.adapter.harvest;

import com.kineticdata.bridgehub.adapter.BridgeAdapter;
import com.kineticdata.bridgehub.adapter.BridgeError;
import com.kineticdata.bridgehub.adapter.BridgeRequest;
import com.kineticdata.bridgehub.adapter.BridgeUtils;
import com.kineticdata.bridgehub.adapter.Count;
import com.kineticdata.bridgehub.adapter.Record;
import com.kineticdata.bridgehub.adapter.RecordList;
import com.kineticdata.commons.v1.config.ConfigurableProperty;
import com.kineticdata.commons.v1.config.ConfigurablePropertyMap;
import java.io.IOException;
import java.net.URLEncoder;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.LoggerFactory;

public class HarvestAdapter implements BridgeAdapter {
    /*----------------------------------------------------------------------------------------------
     * PROPERTIES
     *--------------------------------------------------------------------------------------------*/
    
    /** Defines the adapter display name */
    public static final String NAME = "Harvest Application Bridge";
    
    /** Defines the logger */
    protected static final org.slf4j.Logger logger = LoggerFactory.getLogger(HarvestAdapter.class);
    
    /** Defines the collection of property names for the adapter */
    public static class Properties {
        public static final String PROPERTY_USERNAME = "Username";
        public static final String PROPERTY_PASSWORD = "Password";
        public static final String PROPERTY_HARVEST_ACCOUNT = "Your Harvest App Account";

    }
    
    private final ConfigurablePropertyMap properties = new ConfigurablePropertyMap(
        new ConfigurableProperty(Properties.PROPERTY_USERNAME).setIsRequired(true),
        new ConfigurableProperty(Properties.PROPERTY_PASSWORD).setIsRequired(true).setIsSensitive(true),
        new ConfigurableProperty(Properties.PROPERTY_HARVEST_ACCOUNT).setIsRequired(true)
    );

    // Local variables to store the property values in
    private String username;
    private String password;
    private String harvestAccount;
    private String harvestEndpoint;
    
    /*---------------------------------------------------------------------------------------------
     * SETUP METHODS
     *-------------------------------------------------------------------------------------------*/
    
    @Override
    public void initialize() throws BridgeError {
        // Initializing the variables with the property values that were passed
        // when creating the bridge so that they are easier to use
        this.username = properties.getValue(Properties.PROPERTY_USERNAME);
        this.password = properties.getValue(Properties.PROPERTY_PASSWORD);

        this.harvestEndpoint = "https://" + properties.getValue(Properties.PROPERTY_HARVEST_ACCOUNT).replaceAll("/\\z", "")
            + ".harvestapp.com";
    }

    @Override
    public String getName() {
        return NAME;
    }
    
    @Override
    public String getVersion() {
        // Bridgehub uses this version instead of the Maven version when 
        // displaying it in the console
        return "1.0.0";
    }
    
    @Override
    public void setProperties(Map<String,String> parameters) {
        // This should always be the same unless there are special circumstances
        // for changing it
        properties.setValues(parameters);
    }
    
    @Override
    public ConfigurablePropertyMap getProperties() {
        // This should always be the same unless there are special circumstances
        // for changing it
        return properties;
    }
    
    // Structures that are valid to use in the bridge. Used to check against
    // when a method is called to make sure that the Structure the user is
    // attempting to call is valid
    // TODO: should we add "Contacts", "Invoices", "Expenses", ,"Daily"(Time Enteries), "Projects"(Time Report), "Projects"(Expense Report)
    public static final List<String> VALID_STRUCTURES = Arrays.asList(new String[] {
        "Clients","Projects","Tasks","Task Assignments","Users","User Assignments"
    });
    
    /*---------------------------------------------------------------------------------------------
     * IMPLEMENTATION METHODS
     *-------------------------------------------------------------------------------------------*/

    @Override
    public Count count(BridgeRequest request) throws BridgeError {
        // Log the access
        logger.trace("Counting records");
        logger.trace("  Structure: " + request.getStructure());
        logger.trace("  Query: " + request.getQuery());
        
        // Check if the inputted structure is valid
        if (!VALID_STRUCTURES.contains(request.getStructure())) {
            throw new BridgeError("Invalid Structure: '" + request.getStructure() + "' is not a valid structure");
        }
        
        // Parse the query and exchange out any parameters with their parameter values
        HarvestQualificationParser parser = new HarvestQualificationParser();
        String query = parser.parse(request.getQuery(),request.getParameters());
       
        // Retrieve the objects based on the structure from the source
        String output = getResource(buildSearchUrl(request.getStructure(),query));
        logger.trace("Count Output: "+output);

        // Parse the response string into a JSONObject
        JSONArray objects = (JSONArray)JSONValue.parse(output);
        
        // Get the number of elements in the returned array
        Integer count = objects.size();
        
        // Create and return a count object that contains the count
        return new Count(count);
    }

    @Override
    public Record retrieve(BridgeRequest request) throws BridgeError {
        // Log the access
        logger.trace("Retrieving Kinetic Request CE Record");
        logger.trace("  Structure: " + request.getStructure());
        logger.trace("  Query: " + request.getQuery());
        logger.trace("  Fields: " + request.getFieldString());
        
        // Check if the inputted structure is valid
        if (!VALID_STRUCTURES.contains(request.getStructure())) {
            throw new BridgeError("Invalid Structure: '" + request.getStructure() + "' is not a valid structure");
        }
        
        // Parse the query and exchange out any parameters with their parameter values
        HarvestQualificationParser parser = new HarvestQualificationParser();
        String query = parser.parse(request.getQuery(),request.getParameters());

        // Retrieve the objects based on the structure from the source
        String output = getResource(buildRetrieveUrl(request.getStructure(),query));
        logger.trace("Retrieve Output: "+output);
        
        // Parse the response string into a JSONObject
        JSONObject object = (JSONObject)JSONValue.parse(output);
        
        List<String> fields = request.getFields();
        if (fields == null) { 
            fields = new ArrayList();
        }
        
        // If no keys where provided to the search then we return all properties
        JSONObject obj = (JSONObject)(object).get(getPropertyName(object));
        if(fields.isEmpty()){
            fields.addAll(obj.keySet());
        }
        
        // If specific fields were specified then we remove all of the nonspecified properties from the object.
        Set<Object> removeKeySet = new HashSet<Object>();
        for(Object key: obj.keySet()){
            if(fields.contains(key)){
                continue;
            }else{
                logger.trace("Remove Key: "+key);
                removeKeySet.add(key);
            }
        }
        obj.keySet().removeAll(removeKeySet);
        
        Object[] keys = obj.keySet().toArray();
        JSONObject convertedObj = convertValues(obj,keys);
        
        // Create a Record object from the responce JSONObject
        Record record;
        if (convertedObj != null) {
            record = new Record(convertedObj);
        } else {
            record = new Record();
        }
        
        // Return the created Record object
        return record;
    }

    @Override
    public RecordList search(BridgeRequest request) throws BridgeError {
        // Log the access
        logger.trace("Searching Records");
        logger.trace("  Structure: " + request.getStructure());
        logger.trace("  Query: " + request.getQuery());
        logger.trace("  Fields: " + request.getFieldString());
        
        // Check if the inputted structure is valid
        if (!VALID_STRUCTURES.contains(request.getStructure())) {
            throw new BridgeError("Invalid Structure: '" + request.getStructure() + "' is not a valid structure");
        }
        
        // Parse the query and exchange out any parameters with their parameter values
        HarvestQualificationParser parser = new HarvestQualificationParser();
        String query = parser.parse(request.getQuery(),request.getParameters());
        
        // Retrieve the objects based on the structure from the source
        String output = getResource(buildSearchUrl(request.getStructure(),query));
        logger.trace("Search Output: "+output);

        // Parse the response string into a JSONObject
        JSONArray objects = (JSONArray)JSONValue.parse(output);

        // Create a List of records that will be used to make a RecordList object
        List<Record> recordList = new ArrayList<Record>();
        
        // If the user doesn't enter any values for fields we return all of the fields
        List<String> fields = request.getFields();
        if (fields == null) { 
            fields = new ArrayList();
        }

        if(objects.isEmpty() != true){
            String propertyName = "";
            JSONObject firstObj = (JSONObject)objects.get(0);

            propertyName = getPropertyName(firstObj);
            JSONObject keysObj = null;
            // If no keys where provided to the search then we return all properties
            if(fields.isEmpty()){
                keysObj = (JSONObject)(firstObj).get(propertyName);
                fields.addAll(keysObj.keySet());
            }
            
            Object[] keys =  keysObj.keySet().toArray();
            
            // Iterate through the responce objects and make a new Record for each.
            for (Object o : objects) {
                JSONObject object = (JSONObject)((JSONObject)o).get(propertyName);

                JSONObject convertedObj = convertValues(object,keys);
                Record record;
                if (convertedObj != null) {
                    record = new Record(convertedObj);
                } else {
                    record = new Record();
                }
                // Add the created record to the list of records
                recordList.add(record);
            }
        }
        
        // Return the RecordList object
        return new RecordList(fields, recordList);
    }
    
    /*----------------------------------------------------------------------------------------------
     * HELPER METHODS
     *--------------------------------------------------------------------------------------------*/

    // Escape query helper method that is used to escape queries that have spaces
    // and other characters that need escaping to form a complete URL
    private String escapeQuery(Map<String,String> queryMap) {
        Object[] keyList =  queryMap.keySet().toArray();
        String queryStr = "";
        for (Object key: keyList) {
            String keyStr = key.toString().trim().replaceAll(" ","+");
            String value = queryMap.get(key);
            queryStr += keyStr+"="+URLEncoder.encode(value)+"&";
        }
        logger.trace("Query String: "+queryStr);
        return queryStr;
    }
    
    // Build a map of queries from the request.  Some of the queries will be used to build the url and some will get passed on to harvest.
    private Map<String,String> getQueryMap(String query) throws BridgeError {
        Map<String,String> queryMap = new HashMap<String,String>();
        String[] qSplit = query.split("&");
        for (int i=0;i<qSplit.length;i++) {
            String qPart = qSplit[i];
            String[] keyValuePair = qPart.split("=");
            String key = keyValuePair[0].trim();
            if(queryMap.containsKey(key)){
              throw new BridgeError("A query can only contain one "+key+" parameter.");
            }
            String value = keyValuePair.length > 1 ? keyValuePair[1].trim() : "";
            logger.trace("Query Map Key: "+key+" Value: "+value);
            queryMap.put(key,value);
        }
        return queryMap;
    }
    
    // Each Structure requires it's own specific url and some require that a query parameter be passed in. 
    private String buildSearchUrl(String structure, String query) throws BridgeError{
        Map<String,String> queryMap = getQueryMap(query);
        String url = this.harvestEndpoint;
        if(structure.equals("Clients")){
            url += "/clients";
        }else if(structure.equals("Projects")){
            url += "/projects";
        }else if(structure.equals("Tasks")){
            url += "/tasks";
        }else if(structure.equals("Task Assignments")){
            if(queryMap.containsKey("project_id")){
                url += "/projects/"+queryMap.get("project_id")+"/task_assignments";
                queryMap.remove("project_id");
            }else{
                throw new BridgeError("A project_id is required for the Task Assignmenets structure");
            }
        }else if(structure.equals("Users")){
            url += "/people";
        }else{ // This option is for the User Assignments Structure
            if(queryMap.containsKey("project_id")){
                url += "/projects/"+queryMap.get("project_id")+"/user_assignments";
                queryMap.remove("project_id");
            }else{
                throw new BridgeError("A project_id is required for the User Assignmenets structure");
            }
        }
        if(!queryMap.isEmpty()){
            url += "?"+escapeQuery(queryMap);
        }
        logger.trace("Search url: "+url);
        return url;
    }
    
    // Each Structure requires it's own specific url and some require that a query parameter be passed in. 
    private String buildRetrieveUrl(String structure, String query) throws BridgeError{
        Map<String,String> queryMap = getQueryMap(query);
        String url = this.harvestEndpoint;
        if(structure.equals("Clients")){
            if(queryMap.containsKey("client_id")){
                url += "/clients/"+queryMap.get("client_id");
                queryMap.remove("client_id");
            }else{
                throw new BridgeError("A client_id is required to retrieve a client");
            }
        }else if(structure.equals("Projects")){
            if(queryMap.containsKey("project_id")){
                url += "/projects/"+queryMap.get("project_id");
                queryMap.remove("project_id");
            }else{
                throw new BridgeError("A project_id is required to retrieve a project");
            }
        }else if(structure.equals("Tasks")){
            url += "/tasks";
            if(queryMap.containsKey("task_id")){
                url += "/tasks/"+queryMap.get("task_id");
                queryMap.remove("task_id");
            }else{
                throw new BridgeError("A task_id is required to retrieve a task");
            }
        }else if(structure.equals("Task Assignments")){
            if(queryMap.containsKey("project_id") && queryMap.containsKey("task_assignment_id")){
                url += "/projects/"+queryMap.get("project_id")+"/task_assignments/"+queryMap.get("task_assignment_id");
                queryMap.remove("project_id");
                queryMap.remove("task_assignment_id");
            }else{
                throw new BridgeError("A project_id and task_assignment_id are required to retrieve a task assignment");
            }
        }else if(structure.equals("Users")){
            if(queryMap.containsKey("user_id")){
                url += "/people/"+queryMap.get("user_id");
                queryMap.remove("user_id");
            }else{
                throw new BridgeError("A user_id is required to retrieve a user");
            }
        }else{ // This option is for the User Assignment Structure
            if(queryMap.containsKey("project_id") && queryMap.containsKey("user_assignment_id")){
                url += "/projects/"+queryMap.get("project_id")+"/user_assignments"+queryMap.get("user_assignment_id");
                queryMap.remove("project_id");
                queryMap.remove("user_assignment_id");
            }else{
                throw new BridgeError("A project_id and user_assignment_id are required to retrieve users assignment");
            }
        }
        logger.trace("Retrieve url: "+url);
        return url;
    }
    
    // Count Search and Retrieve get the resoucre the same and use the same output object.
    private String getResource(String url) throws BridgeError {
        // Initialize the HTTP Client,Response, and HTTP GET objects
        HttpClient client = new DefaultHttpClient();
        HttpResponse response;
        HttpGet get = new HttpGet(url);

        // Append HTTP BASIC Authorization header to HttpGet call
        String creds = this.username + ":" + this.password;
        byte[] basicAuthBytes = Base64.encodeBase64(creds.getBytes());
        get.setHeader("Authorization", "Basic " + new String(basicAuthBytes));
        get.setHeader("Accept", "application/json");
        
        // Make the call to the source to retrieve data and convert the response
        // from a HttpEntity object into a Java String
        String output = "";
        try {
            response = client.execute(get);
            HttpEntity entity = response.getEntity();
            output = EntityUtils.toString(entity);
            int responseCode = response.getStatusLine().getStatusCode();
            logger.trace("Request response code: " + responseCode);
            if(responseCode == 404){
                throw new BridgeError("404 Page not found at "+url+".");
            }else if(responseCode == 401){
                throw new BridgeError("401 Access on valid.");
            }
        } 
        catch (IOException e) {
            logger.error(e.getMessage());
            throw new BridgeError("Unable to make a connection to Kinetic Core.", e);
        }
        return output;
    }
    
    // Each Structure returns an object with a different property accessor name.  This is a generic method to get that name.
    // If the returned object has multiple properties we throw an error.
    private String getPropertyName(JSONObject obj) throws BridgeError {
        Object[] keyList =  obj.keySet().toArray();
        String key = "";
        if(keyList.length == 1){
            key = keyList[0].toString();
            logger.trace("Key: "+key);
        }else{
           throw new BridgeError("Only one key is valid from responce object.");
        }
        return key;
    }
 
    private JSONObject convertValues(JSONObject obj,Object[] keys){
        for (Object key : keys){
            Object value = obj.get((String)key);
            if (!(value instanceof String)) {
                logger.trace("Converting: " + String.valueOf(value) + " to a string");
                obj.put((String)key, String.valueOf(value));
            }
        } 
        return obj;
    }
}

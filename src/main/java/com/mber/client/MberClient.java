/*
The Jenkins Mber Plugin is free software distributed under the terms of the MIT
license (http://opensource.org/licenses/mit-license.html) reproduced here:

Copyright (c) 2013-2015 Mber

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package com.mber.client;
import hudson.FilePath;
import hudson.model.BuildListener;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.codec.binary.Base64;
import org.jenkinsci.plugins.mber.FileDownloadCallable;
import org.jenkinsci.plugins.mber.FileUploadCallable;

public class MberClient
{
  public static boolean isMberURL(final String url)
  {
    try {
      String jsdl = baseUrlWithPath(url, "jsdl");
      String body = HTTParty.get(jsdl).body;
      JSON json = JSONSerializer.toJSON(body);
      return !json.isEmpty();
    }
    catch (Exception e) {
      return false;
    }
  }

  public static String baseUrlWithPath(final String url, String path) throws MalformedURLException
  {
    if (!path.endsWith("/")) {
      path += "/";
    }
    URL base = new URL(url);
    URL baseUrl = new URL(base.getProtocol() + "://" + base.getAuthority());
    URL resolvedUrl = new URL(baseUrl, path);
    return resolvedUrl.toString();
  }

  private final String url;
  private final String application;
  private String accessToken;
  private String applicationId;
  private String projectId;
  private String buildId;
  private String buildAlias;
  private JSONArray buildStatus;
  private BuildListener listener;
  private final List<HTTParty.Call> callHistory;

  public MberClient(String url, String application)
  {
    this.url = url;
    this.application = application;
    this.callHistory = new ArrayList();
  }

  public MberClient(final JSONObject json)
  {
    this.url = json.getString("url");
    this.application = json.getString("application");
    this.callHistory = new ArrayList();
    setOrClearAccessToken(json);
    setOrClearApplicationId(json);
    setOrClearProjectId(json);
    setOrClearBuildId(json);
    setOrClearBuildAlias(json);
    setOrClearBuildStatus(json);
  }

  public JSONObject toJSON()
  {
    JSONObject json = new JSONObject();
    json.put("url", getURL());
    json.put("application", getRawApplication());
    json.put("access_token", getAccessToken());
    json.put("applicationId", getApplicationId());
    json.put("projectId", getProjectId());
    json.put("buildId", getBuildId());
    json.put("buildAlias", getBuildAlias());
    json.put("buildStatus", getBuildStatus());
    return json;
  }

  public void setListener(BuildListener listener)
  {
    this.listener = listener;
  }

  public BuildListener getListener()
  {
    return listener;
  }

  public String getURL()
  {
    return this.url;
  }

  public String getApplication()
  {
    return resolveAliasOrUUID(getRawApplication());
  }

  public JSONObject login(final String username, final String password)
  {
    JSONObject response = doLogin(username, password, getApplication());
    if (response.getString("status").equals("Success")) {
      return response;
    }
    // It's possible we have an alias that's also a UUID. Retry the login as an alias.
    if (isUUID(getRawApplication())) {
      return doLogin(username, password, makeAlias(getRawApplication()));
    }
    return response;
  }

  private JSONObject doLogin(final String username, final String password, final String clientId)
  {
    JSONObject data = new JSONObject();
    data.put("username", username);
    data.put("password", password);
    data.put("grant_type", "password");
    data.put("client_id", clientId);
    data.put("transactionId", generateTransactionId());
    JSONObject jsonResponse = post("service/json/oauth/accesstoken", data);
    setOrClearAccessToken(jsonResponse);
    setOrClearApplicationId(jsonResponse);
    return jsonResponse;
  }

  // Creates a link to the given file in Mber Drive. An existing link with a
  // matching name can optionally be overwritten. In the case of an overwrite,
  // any new tags will be added to the existing link's tags.
  public JSONObject link(final FilePath path, final String directory, final String name, final String[] tags, final boolean overwrite)
  {
    JSONObject data = new JSONObject();
    data.put("directoryId", directory);
    data.put("name", name);
    data.put("uri", path.getRemote());
    data.put("tags", tags);
    data.put("access_token", getAccessToken());
    data.put("transactionId", generateTransactionId());
    JSONObject response = post("service/json/data/documentlink", data);

    // Handle duplicates by reading the directory and finding the file with the matching name.
    if (response.getString("status").equals("Duplicate") && overwrite) {
      String documentId = listFiles(directory).get(name);
      if (documentId != null && !documentId.isEmpty()) {
        // DocumentLink.Update has a "tagsToAdd" field instead of "tags".
        data.put("tagsToAdd", tags);
        data.remove("tags");
        response = put("service/json/data/documentlink", documentId, data);
      } else {
        final String error = String.format("Duplicate link with name %s in directory %s wasn't found.", name, directory);
        response = MberJSON.failed(error);
      }
    }

    return response;
  }

  public JSONObject upload(final String path, final String directory, final String name, final String[] tags, final boolean overwrite)
  {
    return upload(new FilePath(new File(path)), directory, name, tags, overwrite);
  }

  public JSONObject upload(final FilePath path, final String directory, final String name, final String[] tags, final boolean overwrite)
  {
    try {
      JSONObject data = new JSONObject();
      data.put("name", name);
      data.put("size", path.length());
      data.put("directoryId", directory);
      data.put("access_token", getAccessToken());
      data.put("transactionId", generateTransactionId());
      data.put("tags", tags);
      JSONObject response = post("service/json/data/upload", data);
      if (response.getString("status").equals("Success")) {
        response = path.act(new FileUploadCallable(response.getString("url"), getListener()));
      } else if (response.getString("status").equals("Duplicate") && overwrite) {
        response = readdir(directory);
        if (response.getString("status").equals("Success") && response.has("result")) {
          JSONObject result = response.getJSONObject("result");
          if (result.has("documents")) {
            JSONArray documents = result.getJSONArray("documents");
            Iterator<JSONObject> itr = documents.iterator();
            while (itr.hasNext()) {
              JSONObject item = itr.next();
              if (item.has("name") && item.getString("name").equals(name) && item.has("documentId")) {
                response = put("service/json/data/upload/", item.getString("documentId"), data);
                if (response.getString("status").equals("Success")) {
                  response = path.act(new FileUploadCallable(response.getString("url"), getListener()));
                }
                break;
              }
            }
          }
        }
      }
      return response;
    }
    catch (InterruptedException e) {
      return MberJSON.failed(e);
    }
    catch (IOException e) {
      return MberJSON.failed(e);
    }
  }

  public JSONObject upload(final JSONObject content, final String directory, final String name, final String[] tags)
  {
    byte[] base64content = content.toString().getBytes();
    base64content = Base64.encodeBase64(base64content);

    JSONObject data = new JSONObject();
    data.put("name", name);
    data.put("content", new String(base64content));
    data.put("directoryId", directory);
    data.put("access_token", getAccessToken());
    data.put("transactionId", generateTransactionId());
    data.put("tags", tags);
    return post("service/json/data/document", data);
  }

  public JSONObject getBuildCountSince(final String name, final Date startDate)
  {
    JSONObject data = new JSONObject();
    data.put("eventName", "AppEvent.tests."+name);
    data.put("eventType", "CREATE");
    data.put("timeUnit", "MINUTES");
    data.put("startDate", startDate.getTime());
    data.put("access_token", getAccessToken());
    return get("service/json/metrics/countovertime", "", data);
  }

  public JSONObject publishTestResults(final JSONObject json)
  {
    JSONArray countFields = new JSONArray();
    addTestCount(countFields, json, "failCount");
    addTestCount(countFields, json, "skipCount");
    addTestCount(countFields, json, "passCount");
    addTestCount(countFields, json, "totalCount");

    JSONArray historicalIds = new JSONArray();
    historicalIds.add(getBuildId());

    JSONObject event = new JSONObject();
    event.put("name", "tests");
    event.put("data", getBuildId());
    event.put("applicationId", getApplicationId());
    event.put("historicalIds", historicalIds);
    event.put("countFields", countFields);
    event.put("permanent", true);
    event.put("access_token", getAccessToken());

    return post("service/json/eventstream/appevent", event);
  }

  private static void addTestCount(JSONArray counts, final JSONObject json, final String key)
  {
    if (json.has(key)) {
      JSONObject data = new JSONObject();
      data.put("name", key);
      data.put("count", json.getInt(key));
      counts.add(data);
    }
  }

  public JSONObject mkpath(String path)
  {
    // Paths always start from the application root, with folders names split by forward slashes.
    path = path.replaceAll("//+", "/");
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }

    String parent = getApplicationId();
    JSONObject response = new JSONObject();
    String[] folders = path.split("/");
    String alias = "";
    for (int i = 0; i < folders.length; ++i) {
      alias += folders[i] + "/";
      response = mkdir(folders[i], parent, alias);
      if (!response.getString("status").equals("Success")) {
        break;
      }
      parent = response.getString("directoryId");
    }
    return response;
  }

  public JSONObject mkproject(final String name, final String description)
  {
    JSONObject data = new JSONObject();
    data.put("name", name);
    // Setting the alias creates a one-to-one mapping between Mber Projects and Jenkins Jobs.
    data.put("alias", name);
    if (description != null && !description.isEmpty()) {
      data.put("description", description);
    }
    data.put("access_token", getAccessToken());
    data.put("transactionId", generateTransactionId());
    JSONObject response = post("service/json/build/project", data);
    if (response.getString("status").equals("Duplicate")) {
      String thisProjectId = lsproject().get(name);
      if (thisProjectId != null && !thisProjectId.isEmpty()) {
        response.put("status", "Success");
        response.put("projectId", thisProjectId);
      }
    }
    setOrClearProjectId(response);
    return response;
  }

  private Map<String, String> lsproject()
  {
    JSONObject data = new JSONObject();
    data.put("access_token", getAccessToken());
    JSONObject response = get("service/json/build/project", "", data);
    Map<String, String> projects = new HashMap<String, String>();
    if (response.has("results")) {
      JSONArray results = response.getJSONArray("results");
      Iterator<JSONObject> itr = results.iterator();
      while (itr.hasNext()) {
        JSONObject item = itr.next();
        if (item.has("alias")) {
          projects.put(item.getString("alias"), item.getString("projectId"));
        }
        else {
          projects.put(item.getString("name"), item.getString("projectId"));
        }
      }
    }
    return projects;
  }

  public JSONObject mkbuild(final int number, final String description, final String alias)
  {
    return mkbuild(Integer.toString(number), description, alias);
  }

  public JSONObject mkbuild(final String name, final String description, final String alias, final BuildStatus... statuses)
  {
    recordBuildStatus(statuses);
    setBuildAlias(alias);
    JSONObject data = new JSONObject();
    data.put("name", name);
    // Setting the alias creates a one-to-one mapping between Jenkins Builds and Mber Builds.
    // An empty alias is valid in Mber, but likely to collide. Avoid it.
    if (alias != null && !alias.isEmpty()) {
      data.put("alias", alias);
    }
    if (description != null && !description.isEmpty()) {
      data.put("description", description);
    }
    data.put("status", getBuildStatus());
    data.put("projectId", getProjectId());
    data.put("access_token", getAccessToken());
    data.put("transactionId", generateTransactionId());
    JSONObject response = post("service/json/build/build", data);
    setOrClearBuildId(response);
    return response;
  }

  public JSONObject updateBuild(final String name, final String description, final BuildStatus... statuses)
  {
    recordBuildStatus(statuses);
    JSONObject data = new JSONObject();
    data.put("name", name);
    if (description != null && !description.isEmpty()) {
      data.put("description", description);
    }
    return updateBuild(data);
  }

  public JSONObject setBuildDirectory(final String directoryId)
  {
    JSONArray directories = new JSONArray();
    directories.add(directoryId);
    JSONObject data = new JSONObject();
    data.put("directoryIds", directories);
    return updateBuild(data);
  }

  public JSONObject findDocumentsWithTags(final String[] tags)
  {
    JSONObject data = new JSONObject();
    data.put("tags", tags);
    data.put("access_token", getAccessToken());
    return get("service/json/data/", "document", data);
  }

  public JSONObject download(final FilePath path, final String documentAliasOrUUID, final boolean showProgress)
  {
    try {
      final String documentId = resolveAliasOrUUID(documentAliasOrUUID);
      final JSONObject data = new JSONObject();
      data.put("access_token", getAccessToken());
      final String downloadURL = getMberUrl("service/raw/data/download") + HTTParty.encodeURIComponent(documentId) + HTTParty.toQuery(data);
      return path.act(new FileDownloadCallable(downloadURL, showProgress ? getListener() : null));
    }
    catch (final Exception e) {
      return MberJSON.failed(e);
    }
  }

  private JSONObject updateBuild(JSONObject data)
  {
    // An empty alias is valid in Mber, but likely to collide. Avoid it.
    final String alias = getBuildAlias();
    if (alias != null && !alias.isEmpty()) {
      data.put("alias", alias);
    }
    data.put("buildId", getBuildId());
    data.put("status", getBuildStatus());
    data.put("access_token", getAccessToken());
    data.put("transactionId", generateTransactionId());
    return put("service/json/build/build/", getBuildId(), data);
  }

  private JSONObject mkdir(final String folder, final String parent, final String alias)
  {
    JSONObject data = new JSONObject();
    data.put("name", folder);
    data.put("parent", parent);
    data.put("alias", alias);
    data.put("access_token", getAccessToken());
    data.put("transactionId", generateTransactionId());

    // Check for old directories whose aliases started with a tick first.
    JSONObject result = readdir(String.format("'%s", alias));
    if (!result.getString("status").equals("Success")) {
      // The directory doesn't exist, so try to create it.
      result = post("service/json/data/directory", data);
      if (result.getString("status").equals("Duplicate")) {
        // The directory already exists, so try to read it by alias.
        result = readdir(alias);
        if (!result.getString("status").equals("Success")) {
          // Directory wasn't found by alias, but exists, so look it up by name.
          String folderId = lsdir(parent).get(folder);
          if (folderId != null && !folderId.isEmpty()) {
            result.put("status", "Success");
            result.put("directoryId", folderId);
          }
        }
      }
    }

    // Resolve the directory ID out of read responses.
    if (result.getString("status").equals("Success") && !result.has("directoryId") && result.has("result")) {
      result.put("directoryId", result.getJSONObject("result").getString("directoryId"));
    }

    return result;
  }

  private JSONObject readdir(final String folder) {
    JSONObject data = new JSONObject();
    data.put("access_token", getAccessToken());
    String id = folder;
    if (!isUUID(folder)) {
      id = makeAlias(folder);
    }
    return get("service/json/data/directory/", id, data);
  }

  public JSONObject readDocument(final String documentAliasOrUUID) {
    final String documentId = resolveAliasOrUUID(documentAliasOrUUID);
    JSONObject data = new JSONObject();
    data.put("access_token", getAccessToken());
    return get("service/json/data/document/", documentId, data);
  }

  private Map<String, String> lsdir(final String folder)
  {
    JSONObject response = readdir(folder);
    Map<String, String> directories = new HashMap<String, String>();
    if (response.has("result")) {
      JSONObject results = response.getJSONObject("result");
      if (results.has("directories")) {
        JSONArray folders = results.getJSONArray("directories");
        Iterator<JSONObject> itr = folders.iterator();
        while (itr.hasNext()) {
          JSONObject item = itr.next();
          directories.put(item.getString("name"), item.getString("directoryId"));
        }
      }
    }
    return directories;
  }

  // Returns a map from name to UUID of all the files in a folder.
  // Mber doesn't allow duplicate file names in a folder, so this is safe.
  private Map<String, String> listFiles(final String folder)
  {
    JSONObject response = readdir(folder);
    Map<String, String> documents = new HashMap<String, String>();
    if (response.has("result")) {
      JSONObject result = response.getJSONObject("result");
      if (result.has("documents")) {
        JSONArray files = result.getJSONArray("documents");
        Iterator<JSONObject> itr = files.iterator();
        while (itr.hasNext()) {
          JSONObject item = itr.next();
          documents.put(item.getString("name"), item.getString("documentId"));
        }
      }
    }
    return documents;
  }

  private JSONObject get(final String service, final String resource, final JSONObject data)
  {
    String mberResponse = "";
    try {
      String endpoint = getMberUrl(service) + HTTParty.encodeURIComponent(resource);
      HTTParty.Call call = HTTParty.get(endpoint, data);
      recordCall(call);
      mberResponse = call.body;
      return parseResponse(mberResponse);
    }
    catch (MalformedURLException e) {
      JSONObject error = new JSONObject();
      error.put("status", "Failed");
      error.put("error", "Invalid Mber URL: "+this.url);
      return error;
    }
    catch (Exception e) {
      JSONObject error = new JSONObject();
      error.put("status", "Failed");
      error.put("error", mberResponse);
      return error;
    }
  }

  private JSONObject put(final String service, final String resource, final JSONObject data)
  {
    String mberResponse = "";
    try {
      String endpoint = getMberUrl(service) + HTTParty.encodeURIComponent(resource);
      HTTParty.Call call = HTTParty.put(endpoint, data);
      recordCall(call);
      mberResponse = call.body;
      return parseResponse(mberResponse);
    }
    catch (MalformedURLException e) {
      JSONObject error = new JSONObject();
      error.put("status", "Failed");
      error.put("error", "Invalid Mber URL: "+this.url);
      return error;
    }
    catch (Exception e) {
      JSONObject error = new JSONObject();
      error.put("status", "Failed");
      error.put("error", mberResponse);
      return error;
    }
  }

  private JSONObject post(final String endpoint, final JSONObject data)
  {
    String mberResponse = "";
    try {
      HTTParty.Call call = HTTParty.post(getMberUrl(endpoint), data);
      recordCall(call);
      mberResponse = call.body;
      return parseResponse(mberResponse);
    }
    catch (MalformedURLException e) {
      JSONObject error = new JSONObject();
      error.put("status", "Failed");
      error.put("error", "Invalid Mber URL: "+this.url);
      return error;
    }
    catch (Exception e) {
      JSONObject error = new JSONObject();
      error.put("status", "Failed");
      error.put("error", mberResponse);
      return error;
    }
  }

  private JSONObject parseResponse(final String response)
  {
    JSONObject json = (JSONObject)JSONSerializer.toJSON(response);
    if (!json.has("status")) {
      json.put("status", "Failed");
    }
    // Success! Return the JSON response.
    if (json.getString("status").equals("Success")) {
      return json;
    }
    // Failed! Make sure we have an error message.
    if (json.has("error") && !json.getString("error").isEmpty()) {
      return json;
    }
    if (json.has("message") && !json.getString("message").isEmpty()) {
      json.put("error", json.getString("message"));
      return json;
    }
    if (json.has("invalid") && !json.getString("invalid").isEmpty()) {
      json.put("error", "Invalid " + json.getString("invalid"));
      return json;
    }
    json.put("error", response);
    return json;
  }

  public static String generateTransactionId()
  {
    UUID uuid = UUID.randomUUID();
    long msb = uuid.getMostSignificantBits();
    long lsb = uuid.getLeastSignificantBits();
    byte[] buffer = new byte[16];
    for (int i = 0; i < 8; ++i) {
      buffer[i] = (byte)(msb >> 8 * (7 - i));
    }
    for (int i = 8; i < 16; ++i) {
      buffer[i] = (byte)(lsb >> 8 * (7 - i));
    }
    return new String(Base64.encodeBase64(buffer));
  }

  private String getMberUrl(String endpoint) throws MalformedURLException
  {
    return baseUrlWithPath(this.url, endpoint);
  }

  public String resolveAliasOrUUID(final String value)
  {
    if (isAlias(value)) {
      return value;
    }
    if (isUUID(value)) {
      return value;
    }
    return makeAlias(value);
  }

  private String makeAlias(final String value)
  {
    return "'" + value;
  }

  private boolean isAlias(final String value)
  {
    return value.startsWith("'");
  }

  private boolean isUUID(final String value)
  {
    return value.matches("^[a-zA-Z0-9-_]{22}$");
  }

  private String getRawApplication()
  {
    return application;
  }

  public String getApplicationId()
  {
    return applicationId;
  }

  public List<HTTParty.Call> getCallHistory()
  {
    return callHistory;
  }

  private void recordCall(final HTTParty.Call call)
  {
    callHistory.add(call);
  }

  private void setOrClearApplicationId(final JSONObject json)
  {
    if (json.has("applicationId")) {
      applicationId = json.getString("applicationId");
    }
    else {
      applicationId = "";
    }
  }

  public String getAccessToken()
  {
    return accessToken;
  }

  private void setOrClearAccessToken(JSONObject json)
  {
    if (json.has("access_token")) {
      accessToken = json.getString("access_token");
    }
    else {
      accessToken = "";
    }
  }

  private String getProjectId()
  {
    return projectId;
  }

  private void setOrClearProjectId(final JSONObject json)
  {
    if (json.has("projectId")) {
      projectId = json.getString("projectId");
    }
    else {
      projectId = "";
    }
  }

  private String getBuildId()
  {
    return buildId;
  }

  private void setOrClearBuildId(final JSONObject json)
  {
    if (json.has("buildId")) {
      buildId = json.getString("buildId");
    }
    else {
      buildId = "";
    }
  }

  private String getBuildAlias()
  {
    return this.buildAlias;
  }

  private void setBuildAlias(final String alias)
  {
    this.buildAlias = alias;
  }

  private void setOrClearBuildAlias(final JSONObject json)
  {
    if (json.has("buildAlias")) {
      setBuildAlias(json.getString("buildAlias"));
    }
    else {
      setBuildAlias("");
    }
  }

  private JSONArray getBuildStatus()
  {
    return buildStatus;
  }

  private void setBuildStatus(final JSONArray buildStatus)
  {
    this.buildStatus = buildStatus;
  }

  private void setOrClearBuildStatus(final JSONObject json)
  {
    if (json.has("buildStatus")) {
      setBuildStatus(json.getJSONArray("buildStatus"));
    }
    else {
      setBuildStatus(new JSONArray());
    }
  }

  private void recordBuildStatus(final BuildStatus... statuses)
  {
    JSONArray statusList = new JSONArray();
    for (BuildStatus status : statuses) {
      statusList.add(status.toString());
    }
    setBuildStatus(statusList);
  }
}

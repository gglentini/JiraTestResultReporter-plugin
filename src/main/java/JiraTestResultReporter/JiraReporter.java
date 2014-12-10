package JiraTestResultReporter;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.TestResultAction;
import hudson.util.FormValidation;

import org.json.JSONArray;
import org.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.PrintStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

public class JiraReporter extends Notifier {

    //private static final Integer[] JIRA_SUCCESS_CODES = {201, 200};
    private static final int JIRA_CREATED_CODE = 201;
    private static final int JIRA_SUCCESS_CODE = 200;
    private static final String JIRA_API_URL = "rest/api/latest/";
    private static final String PluginName = new String("[JiraTestResultReporter]");
    private final String pInfo = String.format("%s [INFO]", PluginName);
    private final String pDebug = String.format("%s [DEBUG]", PluginName);
    private final String pVerbose = String.format("%s [DEBUGVERBOSE]", PluginName);
    private final String prefixError = String.format("%s [ERROR]", PluginName);

    public String projectKey;
    public String serverAddress;
    public String username;
    public String password;
    public boolean queryJira;
    public boolean debugFlag;
    public boolean verboseDebugFlag;
    public boolean createAllFlag;
    private transient FilePath workspace;

    @DataBoundConstructor
    public JiraReporter(String projectKey,
                        String serverAddress,
                        String username,
                        String password,
                        boolean queryJira,
                        boolean createAllFlag,
                        boolean debugFlag,
                        boolean verboseDebugFlag) {
        if (serverAddress.endsWith("/")) {
            this.serverAddress = serverAddress;
        } else {
            this.serverAddress = serverAddress + "/";
        }

        this.projectKey = projectKey;
        this.username = username;
        this.password = password;

        this.queryJira = queryJira;

        this.verboseDebugFlag = verboseDebugFlag;
        if (verboseDebugFlag) {
            this.debugFlag = true;
        } else {
            this.debugFlag = debugFlag;
        }
        
        this.createAllFlag = createAllFlag;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }


    @Override
    public boolean perform(final AbstractBuild build,
                           final Launcher launcher,
                           final BuildListener listener) {
        PrintStream logger = listener.getLogger();
        logger.printf("%s Examining test results...%n", pInfo);
        debugLog(listener,
                 String.format("Build result is %s%n",
                    build.getResult().toString())
                );
        this.workspace = build.getWorkspace();
        debugLog(listener,
                 String.format("%s Workspace is %s%n", pInfo, this.workspace.toString())
                );

        TestResultAction testResultAction = build.getAction(TestResultAction.class);
        if (testResultAction == null) {
            logger.printf("%s no test results found; nothing to do.%n", pInfo);
        }
        else {
            List<CaseResult> failedTests = testResultAction.getFailedTests();
            printResultItems(failedTests, listener);

            if (!this.queryJira) {
                //back to the old behavior
                createJiraIssue(failedTests, build, listener);
            }
            else {
                // interact with Jira
                updateJira(failedTests, build, listener);
            }
            logger.printf("%s Done.%n", pInfo);
        }
        return true;
    }

    private void printResultItems(final List<CaseResult> failedTests,
                                  final BuildListener listener) {
        if (!this.debugFlag) {
            return;
        }
        PrintStream out = listener.getLogger();
        for (CaseResult result : failedTests) {
            out.printf("%s projectKey: %s%n", pDebug, this.projectKey);
            out.printf("%s errorDetails: %s%n", pDebug, result.getErrorDetails());
            out.printf("%s fullName: %s%n", pDebug, result.getFullName());
            out.printf("%s simpleName: %s%n", pDebug, result.getSimpleName());
            out.printf("%s title: %s%n", pDebug, result.getTitle());
            out.printf("%s packageName: %s%n", pDebug, result.getPackageName());
            out.printf("%s name: %s%n", pDebug, result.getName());
            out.printf("%s className: %s%n", pDebug, result.getClassName());
            out.printf("%s failedSince: %d%n", pDebug, result.getFailedSince());
            out.printf("%s status: %s%n", pDebug, result.getStatus().toString());
            out.printf("%s age: %s%n", pDebug, result.getAge());
            out.printf("%s ErrorStackTrace: %s%n", pDebug, result.getErrorStackTrace());

            String affectedFile = result.getErrorStackTrace().replace(this.workspace.toString(), "");
            out.printf("%s affectedFile: %s%n", pDebug, affectedFile);
            out.printf("%s ----------------------------%n", pDebug);
        }
    }

    void debugLog(final BuildListener listener, final String message) {
        if (!this.debugFlag) {
            return;
        }
        PrintStream logger = listener.getLogger();
        logger.printf("%s %s%n", pDebug, message);
    }

    HttpResponse<JsonNode> getJiraTickets(final CaseResult failedTest,
                                          final BuildListener listener) throws UnirestException {
        String jiraAPIUrlSearch = this.serverAddress + JIRA_API_URL + "search";

        // GET search
        // using as query string some values that should reduce (hopefully to 1) the number of tickets found
        // looking only in the specified JIRA project for Open or In Progress tickets
        // reported by the user set in the plugin (ideally an integration account)
        // and with a regexp "Test <test name> failed"
        // TODO: change to a POST request?
        HttpResponse<JsonNode> jsonResponse = Unirest.get(jiraAPIUrlSearch)
                //.header("accept", "application/json")
                .basicAuth(this.username, this.password)
                .queryString("jql", "project = " + this.projectKey + " AND status in (Open, \"In Progress\") " +
                        "AND reporter in (" + this.username + ") AND summary ~ \"Test " + failedTest.getName() + " failed\"")
                .queryString("fields", "summary")
                .asJson();

        debugLog(listener,
                String.format("statusLine: %s%n",
                        jsonResponse.getStatusText())
        );
        debugLog(listener,
                String.format("statusCode: %d%n",
                        jsonResponse.getStatus())
        );

        if (jsonResponse.getStatus() != JIRA_SUCCESS_CODE) {
            throw new RuntimeException(this.prefixError + " Failed while searching for issues: HTTP error code : " + jsonResponse.getStatus());
        }

        return jsonResponse;
    }

    HttpResponse<JsonNode> createJiraComment(final JSONObject issue,
                                             final CaseResult failedTest,
                                             final AbstractBuild build,
                                             final BuildListener listener) throws UnirestException {
        PrintStream logger = listener.getLogger();
        String jiraAPIUrlIssueComment = this.serverAddress + JIRA_API_URL +
                                        "issue/" + issue.getString("key") + "/comment";

        JSONObject body = new JSONObject();
        body.put("body", "The same test has failed in " + build.getAbsoluteUrl() +
                 "\n\nError message: {noformat}\n" + failedTest.getErrorDetails() + "\n{noformat}\n\n" +
                "Stacktrace:  {noformat}\n" + failedTest.getErrorStackTrace().replace(this.workspace.toString(), "") +
                "\n{noformat}\n\n");
        JsonNode jsonBody = new JsonNode(body.toString());

        logger.printf("%s Adding a comment to %s\n", pInfo, this.serverAddress + "browse/" + issue.getString("key"));
        HttpResponse<JsonNode> addCommentResponse = Unirest.post(jiraAPIUrlIssueComment)
                //.header("accept", "application/json")
                .header("content-type", "application/json")
                .basicAuth(this.username, this.password)
                .body(jsonBody)
                .asJson();

        debugLog(listener,
                String.format("statusLine: %s%n",
                        addCommentResponse.getStatusText())
        );
        debugLog(listener,
                String.format("statusCode: %d%n",
                        addCommentResponse.getStatus())
        );

        if (addCommentResponse.getStatus() != JIRA_CREATED_CODE) {
            throw new UnirestException(this.prefixError + " Failed while adding a comment: HTTP error code : " + addCommentResponse.getStatus());
        }

        return addCommentResponse;
    }

    HttpResponse<JsonNode> createJiraTicket(final CaseResult failedTest,
                                         final AbstractBuild build,
                                         final BuildListener listener) throws UnirestException {
        PrintStream logger = listener.getLogger();
        String jiraAPIUrlIssue = this.serverAddress + JIRA_API_URL + "issue";

        // create a new ticket
        // ticket fields
        String summary = "Test " + failedTest.getName() + " failed";
        String description = "Test class: " + failedTest.getClassName() + "\n\n" +
                "Jenkins job: " + build.getAbsoluteUrl() + "\n\n\n" +
                "Error message: {noformat}\n" + failedTest.getErrorDetails() + "\n{noformat}\n\n" +
                "Stacktrace:  {noformat}\n" + failedTest.getErrorStackTrace().replace(this.workspace.toString(), "") +
                "\n{noformat}\n\n";

        // create a JSON structure out of the fields
        JSONObject jsonSubFields = new JSONObject();
        JSONObject projectFields = new JSONObject();
        JSONObject issueTypeFields = new JSONObject();
        JSONObject jsonFields = new JSONObject();
        projectFields.put("key", this.projectKey);
        issueTypeFields.put("name", "Bug");
        jsonSubFields.put("summary", summary).put("description", description).put("project", projectFields)
                        .put("issuetype", issueTypeFields);
        jsonFields.put("fields", jsonSubFields);
        JsonNode jsonNodeFields = new JsonNode(jsonFields.toString());

        // make POST issue request
        HttpResponse<JsonNode> createIssueResponse = Unirest.post(jiraAPIUrlIssue)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .basicAuth(this.username, this.password)
                .body(jsonNodeFields)
                .asJson();

        debugLog(listener,
                String.format("statusLine: %s%n",
                        createIssueResponse.getStatusText())
        );
        debugLog(listener,
                String.format("statusCode: %d%n",
                        createIssueResponse.getStatus())
        );

        if (createIssueResponse.getStatus() != JIRA_CREATED_CODE) {
            throw new UnirestException(this.prefixError + " Failed while creating an issue: HTTP error code : " + createIssueResponse.getStatus());
        }

        logger.printf("%s Created %s\n", pInfo, this.serverAddress + "browse/" + createIssueResponse.getBody()
                                                .getObject().getString("key"));
        return createIssueResponse;
    }

     void updateJira(final List<CaseResult> failedTests,
                          final AbstractBuild build,
                          final BuildListener listener) {
         PrintStream logger = listener.getLogger();

        for (CaseResult failedTest : failedTests) {
            try {
                // check for tickets in JIRA
                HttpResponse<JsonNode> jsonResponse = getJiraTickets(failedTest, listener);

                if (jsonResponse.getBody().getObject().getInt("total") != 0) {
                    // ticket already available, add a comment
                    logger.printf("%s Existing ticket(s) found for: %s\n", pInfo, failedTest.getFullName());
                    JSONArray issues = jsonResponse.getBody().getObject().getJSONArray("issues");
                    // create a comment for every ticket found
                    // ideally it should only be 1
                    for (int i=0; i<issues.length(); ++i) {
                        HttpResponse<JsonNode> response = createJiraComment(issues.getJSONObject(i), failedTest, build, listener);
                    }
                }
                else {
                    // create a new ticket
                    logger.printf("%s No ticket(s) found for: %s\n", pInfo, failedTest.getFullName());
                    HttpResponse<JsonNode> response = createJiraTicket(failedTest, build, listener);
                }
            }
            catch (UnirestException e) {
                logger.printf("%s\n", e.getMessage());
                e.printStackTrace();
            }
/*            catch (MalformedURLException e) {
                e.printStackTrace();
            }*/
/*            catch (IOException e) {
                e.printStackTrace();
            }*/
        }
    }

    void createJiraIssue(final List<CaseResult> failedTests,
                         final AbstractBuild build,
                         final BuildListener listener) {
        PrintStream logger = listener.getLogger();
        String url = this.serverAddress + "rest/api/2/issue/";

        for (CaseResult result : failedTests) {
            if ((result.getAge() == 1) || (this.createAllFlag)) {
                debugLog(listener,
                        String.format("Creating issue in project %s at URL %s%n",
                                this.projectKey, url)
                );
                try {
                    HttpResponse<JsonNode> response = createJiraTicket(result, build, listener);
                }
                catch (UnirestException e) {
                    logger.printf("%s\n", e.getMessage());
                    e.printStackTrace();
                }
            } else {
                logger.printf("%s This issue is old; not reporting.%n", pInfo);
            }
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Jira Test Result Reporter";
        }
        
        public FormValidation doCheckProjectKey(@QueryParameter String value) {
        	if (value.isEmpty()) {
        		return FormValidation.error("You must provide a project key.");
        	} else {
        		return FormValidation.ok();
        	}
        }

        public FormValidation doCheckServerAddress(@QueryParameter String value) {
        	if (value.isEmpty()) {
        		return FormValidation.error("You must provide an URL.");
        	}
        	
        	try {
        		new URL(value);
        	} catch (final MalformedURLException e) {
        		return FormValidation.error("This is not a valid URL.");
        	}
        	
        	return FormValidation.ok();
        }
    }
}


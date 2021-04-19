package liquibase.command.core;

import liquibase.Scope;
import liquibase.changelog.*;
import liquibase.command.*;
import liquibase.database.Database;
import liquibase.exception.CommandExecutionException;
import liquibase.hub.HubService;
import liquibase.hub.HubServiceFactory;
import liquibase.hub.HubUpdater;
import liquibase.hub.LiquibaseHubException;
import liquibase.hub.model.Connection;
import liquibase.hub.model.HubChangeLog;
import liquibase.hub.model.Project;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ResourceAccessor;
import liquibase.util.StringUtil;

import java.util.List;
import java.util.UUID;

public class InternalSyncHubCommandStep extends AbstractCommandStep {

    public static final CommandArgumentDefinition<String> URL_ARG;
    public static final CommandArgumentDefinition<String> CHANGELOG_FILE_ARG;
    public static final CommandArgumentDefinition<String> HUB_CONNECTION_ID_ARG;
    public static final CommandArgumentDefinition<String> HUB_PROJECT_ID_ARG;
    public static final CommandArgumentDefinition<Database> DATABASE_ARG;
    public static final CommandArgumentDefinition<Boolean> FAIL_IF_ONLINE_ARG;

    static {
        CommandStepBuilder builder = new CommandStepBuilder(InternalSyncHubCommandStep.class);
        URL_ARG = builder.argument("url", String.class).build();
        CHANGELOG_FILE_ARG = builder.argument("changeLogFile", String.class).required().build();
        HUB_CONNECTION_ID_ARG = builder.argument("hubConnectionId", String.class).build();
        HUB_PROJECT_ID_ARG = builder.argument("hubProjectId", String.class).build();
        DATABASE_ARG = builder.argument("database", Database.class).build();
        FAIL_IF_ONLINE_ARG = builder.argument("failIfOnline", Boolean.class).defaultValue(true).build();
    }

    @Override
    public String[] getName() {
        return new String[]{"internalSyncHub"};
    }

    @Override
    public void run(CommandResultsBuilder resultsBuilder) throws Exception {
        CommandScope commandScope = resultsBuilder.getCommandScope();

        final HubServiceFactory hubServiceFactory = Scope.getCurrentScope().getSingleton(HubServiceFactory.class);
        if (! hubServiceFactory.isOnline()) {
            if (commandScope.getArgumentValue(FAIL_IF_ONLINE_ARG)) {
                throw new CommandExecutionException("The command syncHub requires access to Liquibase Hub: " +
                        hubServiceFactory.getOfflineReason() +".  Learn more at https://hub.liquibase.com");
            } else {
                Scope.getCurrentScope().getUI().sendMessage("Sync skipped, offline");
                return;
            }
        }

        //
        // Check for both connection and project specified
        //
        final String hubConnectionId = commandScope.getArgumentValue(HUB_CONNECTION_ID_ARG);
        if (hubConnectionId != null && commandScope.getArgumentValue(HUB_PROJECT_ID_ARG) != null) {
            String message = "The syncHub command requires only one valid hubConnectionId or hubProjectId or unique URL. Please remove extra values.";
            Scope.getCurrentScope().getLog(getClass()).severe(message);
            throw new CommandExecutionException(message);
        }
        HubChangeLog hubChangeLog = null;
        final HubService hubService = Scope.getCurrentScope().getSingleton(HubServiceFactory.class).getService();
        Connection connectionToSync;
        if (hubConnectionId == null) {
            Project project = null;
            if (StringUtil.isNotEmpty(commandScope.getArgumentValue(CHANGELOG_FILE_ARG))) {
                final ResourceAccessor resourceAccessor = Scope.getCurrentScope().getResourceAccessor();
                final String changelogFile = commandScope.getArgumentValue(CHANGELOG_FILE_ARG);
                final DatabaseChangeLog databaseChangeLog = ChangeLogParserFactory.getInstance().getParser(changelogFile, resourceAccessor).parse(changelogFile, new ChangeLogParameters(), resourceAccessor);
                final String changeLogId = databaseChangeLog.getChangeLogId();

                if (changeLogId == null) {
                    Scope.getCurrentScope().getLog(getClass()).info("Changelog " + changelogFile + " has not been registered with Liquibase Hub. Cannot use it to help determine project.");
                } else {
                    hubChangeLog = hubService.getHubChangeLog(UUID.fromString(changeLogId), "*");
                    if (hubChangeLog == null) {
                        throw new CommandExecutionException("Changelog " + changelogFile + " has an unrecognized changeLogId.");
                    }
                    if (hubChangeLog.isDeleted()) {
                        //
                        // Complain and stop the operation
                        //
                        String message =
                            "\n" +
                            "The operation did not complete and will not be reported to Hub because the\n" +  "" +
                            "registered changelog has been deleted by someone in your organization.\n" +
                            "Learn more at http://hub.liquibase.com";
                        throw new LiquibaseHubException(message);
                    }
                    project = hubChangeLog.getProject();
                }
            }
            else if (commandScope.getArgumentValue(HUB_PROJECT_ID_ARG) != null) {
                project = hubService.getProject(UUID.fromString(commandScope.getArgumentValue(HUB_PROJECT_ID_ARG)));
                if (project == null) {
                    throw new CommandExecutionException("Project Id '" + commandScope.getArgumentValue(HUB_PROJECT_ID_ARG) + "' does not exist or you do not have access to it");
                }
            }
            else {
                Scope.getCurrentScope().getLog(getClass()).info("No project, connection, or changeLogFile specified. Searching for jdbcUrl across the entire organization.");
            }

            final String url = commandScope.getArgumentValue(URL_ARG);
            final Connection searchConnection = new Connection()
                    .setJdbcUrl(url)
                    .setProject(project);

            final List<Connection> connections = hubService.getConnections(searchConnection);
            if (connections.size() == 0) {

                if (project == null) {
                    throw new CommandExecutionException("The url " + url + " does not match any defined connections. To auto-create a connection, please specify a 'changeLogFile=<changeLogFileName>' in liquibase.properties or the command line which contains a registered changeLogId.");
                }

                Connection inputConnection = new Connection();
                inputConnection.setJdbcUrl(url);
                inputConnection.setProject(project);

                connectionToSync = hubService.createConnection(inputConnection);
            } else if (connections.size() == 1) {
                connectionToSync = connections.get(0);
            } else {
                throw new CommandExecutionException("The url " + url + " is used by more than one connection. Please specify 'hubConnectionId=<hubConnectionId>' or 'changeLogFile=<changeLogFileName>' in liquibase.properties or the command line.");
            }
        } else {
            final List<Connection> connections = hubService.getConnections(new Connection().setId(UUID.fromString(hubConnectionId)));
            if (connections.size() == 0) {
                throw new CommandExecutionException("Hub connection Id "+ hubConnectionId + " was either not found, or you do not have access");
            } else {
                connectionToSync = connections.get(0);
            }
        }

        final Database database = commandScope.getArgumentValue(DATABASE_ARG);
        Scope.child(Scope.Attr.database, database, () -> {
            final ChangeLogHistoryService historyService = ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(database);
            final List<RanChangeSet> ranChangeSets = historyService.getRanChangeSets();

            hubService.setRanChangeSets(connectionToSync, ranChangeSets);

        });

        //
        // Tell the user if the HubChangeLog is deactivated
        //
        if (hubChangeLog != null && hubChangeLog.isInactive()) {
            String message =
                "\n" +
                "The command completed and reported to Hub, but the changelog has been deactivated by someone in your organization.\n" +
                "To synchronize your changelog, checkout the latest from source control or run \"deactivatechangelog\".\n" +
                "After that, commands run against this changelog will not be reported to Hub until \"registerchangelog\" is run again.\n" +
                "Learn more at http://hub.liquibase.com";
            Scope.getCurrentScope().getLog(HubUpdater.class).warning(message);
            Scope.getCurrentScope().getUI().sendMessage("WARNING: " + message);
        }
    }

}

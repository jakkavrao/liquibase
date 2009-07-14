package liquibase.integration.commandline;

import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.JdbcConnection;
import liquibase.database.core.HibernateDatabase;
import liquibase.diff.Diff;
import liquibase.diff.DiffResult;
import liquibase.diff.DiffStatusListener;
import liquibase.exception.DatabaseException;
import liquibase.exception.MigrationFailedException;
import liquibase.util.StringUtils;
import liquibase.logging.LogFactory;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Properties;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Common Utilitiy methods used in the CommandLine application and the Maven plugin.
 * These methods were orignally moved from {@link liquibase.integration.commandline.Main} so they could be shared.
 *
 * @author Peter Murray
 */
public class CommandLineUtils {

    public static Database createDatabaseObject(ClassLoader classLoader,
                                                String url,
                                                String username,
                                                String password,
                                                String driver,
                                                String defaultSchemaName,
                                                String databaseClass) throws DatabaseException {
        if (driver == null) {
            driver = DatabaseFactory.getInstance().findDefaultDriver(url);
        }

        try {
            if (url.startsWith("hibernate:")) {
                try {
                    return createHibernateDatabase(classLoader, url);
                } catch (NoClassDefFoundError e) {
                    try {
                        return createHibernateDatabase(Thread.currentThread().getContextClassLoader(), url);
                    } catch (NoClassDefFoundError e1) {
                        throw new MigrationFailedException(null, "Class " + e1.getMessage() + " not found.  Make sure all required Hibernate and JDBC libraries are in your classpath");
                    }
                }
            }

            Driver driverObject;
            DatabaseFactory databaseFactory = DatabaseFactory.getInstance();
            if (databaseClass != null) {
                databaseFactory.register((Database) Class.forName(databaseClass, true, classLoader).newInstance());
            }

            try {
                if (driver == null) {
                    driver = databaseFactory.findDefaultDriver(url);
                }

                if (driver == null) {
                    throw new RuntimeException("Driver class was not specified and could not be determined from the url (" + url + ")");
                }

                driverObject = (Driver) Class.forName(driver, true, classLoader).newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Cannot find database driver: " + e.getMessage());
            }
            Properties info = new Properties();
            if (username != null) {
                info.put("user", username);
            }
            if (password != null) {
                info.put("password", password);
            }

            Connection connection = driverObject.connect(url, info);
            if (connection == null) {
                throw new DatabaseException("Connection could not be created to " + url + " with driver " + driver.getClass().getName() + ".  Possibly the wrong driver for the given database URL");
            }

            Database database = databaseFactory.findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(StringUtils.trimToNull(defaultSchemaName));
            return database;
        } catch (Exception e) {
            throw new DatabaseException(e);
        }
    }

    private static Database createHibernateDatabase(ClassLoader classLoader, String url) throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, ClassNotFoundException {
        return (Database) Class.forName(HibernateDatabase.class.getName(), true, classLoader).getConstructor(String.class).newInstance(url.substring("hibernate:".length()));
    }

    public static void doDiff(Database baseDatabase, Database targetDatabase) throws DatabaseException {
        Diff diff = new Diff(baseDatabase, targetDatabase);
        diff.addStatusListener(new OutDiffStatusListener());
        DiffResult diffResult = diff.compare();

        System.out.println("");
        System.out.println("Diff Results:");
        diffResult.printResult(System.out);
    }

    public static void doDiffToChangeLog(String changeLogFile,
                                         Database baseDatabase,
                                         Database targetDatabase)
            throws DatabaseException, IOException, ParserConfigurationException {
        Diff diff = new Diff(baseDatabase, targetDatabase);
        diff.addStatusListener(new OutDiffStatusListener());
        DiffResult diffResult = diff.compare();

        if (changeLogFile == null) {
            diffResult.printChangeLog(System.out, targetDatabase);
        } else {
            diffResult.printChangeLog(changeLogFile, targetDatabase);
        }
    }

    public static void doGenerateChangeLog(String changeLogFile, Database originalDatabase, String defaultSchemaName, String diffTypes, String author, String context, String dataDir) throws DatabaseException, IOException, ParserConfigurationException {
        Diff diff = new Diff(originalDatabase, defaultSchemaName);
        diff.setDiffTypes(diffTypes);

        diff.addStatusListener(new OutDiffStatusListener());
        DiffResult diffResult = diff.compare();
        diffResult.setChangeSetAuthor(author);
        diffResult.setChangeSetContext(context);
        diffResult.setDataDir(dataDir);

        PrintStream outputStream = System.out;

        if (StringUtils.trimToNull(changeLogFile) != null) {
            File changeFile = new File(changeLogFile);
            outputStream = new PrintStream(changeFile);
        }
        diffResult.printChangeLog(outputStream, originalDatabase);
    }

    private static class OutDiffStatusListener implements DiffStatusListener {

        public void statusUpdate(String message) {
            LogFactory.getLogger().info(message);

        }

    }

}

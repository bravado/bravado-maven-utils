import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.apache.tools.ant.Target;
import org.apache.tools.ant.taskdefs.Echo;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by bob on 10/08/14.
 *
 * @author <a href="mailto:bob@bravado.com.br>Roberto Santacroce Martins</a>
 */
public class BravadoAntMavenUtils {

    private static final String MAVEN_DEPLOY_COMMAND = "mvn deploy:deploy-file -Dfile=%s " +
            "-DartifactId=%s -DgroupId=%s -Dversion=%s " +
            "-Durl=http://maven.bravado.com.br/repository/voice/ " +
            "-DgeneratePom=true " +
            "-DrepositoryId=maven.bravado.com.br";
    private static String LINE_BREAK = "\r\n";

    public static void main(String args[]) throws IOException, ParserConfigurationException, SAXException, TransformerException {

        if (args.length < 2) {
            System.out.println("This tool scan build.xml classpath and generates pom.xml dependencies from a project");
            System.out.println("Usage: projectPath groupId versio");
            return;
        }

        String projectPath = args[0];
        String groupId = args[1];// "ipx-libs";
        String version = args[2]; // "3.5.0";

        System.out.println("Processing ... " + projectPath);

        File antFile = new File(projectPath, "build.xml");

        // TODO do it in execution
        System.getProperties().setProperty("workspace.location", antFile.getParentFile().getParent());

        File dependenciesFile = executeAntTask(antFile);
        convertDepAntToMaven(dependenciesFile, groupId, version);
    }


    /**
     * Execute ant tasks in specific directory, depends on "init" task on build.xml
     *
     * @param buildFile
     * @return
     * @throws FileNotFoundException
     */
    public static File executeAntTask(File buildFile) throws FileNotFoundException {
        Project p = new Project();
        p.setUserProperty("ant.file", buildFile.getAbsolutePath());
        p.init();

        ProjectHelper helper = ProjectHelper.getProjectHelper();
        p.addReference("ant.projectHelper", helper);
        helper.parse(p, buildFile);

        Echo echoTask = new Echo();
        echoTask.setProject(p);
        echoTask.setTaskName("echo");
        echoTask.addText("${toString:classpath}");

        Target target = new Target();
        target.setDepends("init");
        target.setName("show");
        target.addTask(echoTask);
        target.setProject(p);
        p.addTarget("show", target);

        File dependenciesFile = new File(buildFile.getParentFile(), "dependencies.txt");
        FileOutputStream outputStream = new FileOutputStream(dependenciesFile);
        PrintStream printStream = new PrintStream(outputStream);
        DefaultLogger consoleLogger = new DefaultLogger();
        consoleLogger.setErrorPrintStream(System.err);
        consoleLogger.setOutputPrintStream(printStream);
        consoleLogger.setMessageOutputLevel(Project.MSG_INFO);
        p.addBuildListener(consoleLogger);
        p.executeTarget("show");

        return dependenciesFile;
    }

    /**
     * Reads dependencies generated from ant echo classpath tasks and converts it to maven dependency snippet.
     *
     * @param dependenciesFile
     * @param groupId
     * @param version
     * @throws IOException
     */
    public static void convertDepAntToMaven(File dependenciesFile, String groupId, String version) throws IOException, ParserConfigurationException, SAXException, TransformerException {
        BufferedReader bufferedReader = new BufferedReader(new FileReader(dependenciesFile));
        String line = "";
//        StringBuilder mavenSnippets = new StringBuilder();
        StringBuilder mavenDeployCommands = new StringBuilder();

        List<String> mavenDependencies = new ArrayList<String>();

        while (((line = bufferedReader.readLine()) != null)) {
            line = cleanDependenciesLine(line);
            String[] deps = line.split(":");
            for (int x = 0; x < deps.length; x++) {
                File f = new File(deps[x]);
                if (f.exists()) {
                    String artifactId = f.getName();

                    mavenDependencies.add(artifactId.replace(".jar", ""));
//                    mavenSnippets.append(String.format(DEPENDENCY_MAVEN_STRING, groupId, , version));
//                    mavenSnippets.append(LINE_BREAK);

                    mavenDeployCommands.append(String.format(MAVEN_DEPLOY_COMMAND, f.getAbsolutePath(), f.getName().replace(".jar", ""), groupId, version));
                    mavenDeployCommands.append(LINE_BREAK);
                }
            }
        }

        addMavenSnippetsToPOM(dependenciesFile, mavenDependencies, groupId, version);
        System.out.println(mavenDeployCommands.toString());
    }

    /**
     * Modifiy pom.xml and add dependency based on ant classpath
     *
     * @param dependenciesFile
     * @param mavenDependencies
     * @param groupId
     * @param version
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException
     * @throws TransformerException
     */
    private static void addMavenSnippetsToPOM(File dependenciesFile, List<String> mavenDependencies, String groupId, String version) throws ParserConfigurationException, SAXException, IOException, TransformerException {
        File pomFile = new File(dependenciesFile.getParentFile(), "pom.xml");
        if (!pomFile.exists()) {
            // todo create pom.xml with project properties
        }
        // update pom.xml dependency with mavenSnippets

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document document = db.parse(pomFile);

        if (document.getElementsByTagName("dependencies").getLength() == 0) {
            Element dependenciesElement = document.createElement("dependencies");
            document.getElementsByTagName("project").item(0).appendChild(dependenciesElement);
        }

        for (String d : mavenDependencies) {
            Element e = document.createElement("dependency");
            Element groupIdElement = document.createElement("groupId");
            Element artifactIdElement = document.createElement("artifactId");
            Element versionElement = document.createElement("version");

            groupIdElement.setTextContent(groupId);
            artifactIdElement.setTextContent(d);
            versionElement.setTextContent(version);

            e.appendChild(groupIdElement);
            e.appendChild(artifactIdElement);
            e.appendChild(versionElement);

            document.getElementsByTagName("dependencies").item(0).appendChild(e);
        }

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        Result output = new StreamResult(pomFile);
        Source input = new DOMSource(document);
        transformer.transform(input, output);
        System.out.println("pom.xml updated");
    }

    /**
     * Clear dependencies.txt line
     *
     * @param line
     * @return
     */
    private static String cleanDependenciesLine(String line) {
        line = line.replace("init:", "");
        line = line.replace("[echo]", "");
        line = line.replace("show:", "");
        line = line.replace("-3.5.0", "");
        return line;
    }

}

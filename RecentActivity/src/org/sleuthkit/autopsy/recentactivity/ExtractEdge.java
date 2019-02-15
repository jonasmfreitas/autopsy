/*
 *
 * Autopsy Forensic Browser
 *
 * Copyright 2019 Basis Technology Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.recentactivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Level;
import org.openide.modules.InstalledFileLocator;
import org.openide.util.NbBundle.Messages;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.NoCurrentCaseException;
import org.sleuthkit.autopsy.coreutils.ExecUtil;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.NetworkUtils;
import org.sleuthkit.autopsy.coreutils.PlatformUtil;
import org.sleuthkit.autopsy.datamodel.ContentUtils;
import org.sleuthkit.autopsy.ingest.DataSourceIngestModuleProcessTerminator;
import org.sleuthkit.autopsy.ingest.IngestJobContext;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.autopsy.ingest.ModuleDataEvent;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.TskCoreException;

/**
 * Extract the bookmarks, cookies, downloads and history from the Microsoft Edge
 * files
 */
final class ExtractEdge extends Extract {

    private static final Logger logger = Logger.getLogger(ExtractIE.class.getName());
    private final IngestServices services = IngestServices.getInstance();
    private final String moduleTempResultsDir;
    private Content dataSource;
    private IngestJobContext context;

    private static final String ESE_TOOL_NAME = "ESEDatabaseView.exe";
    private static final String EDGE_WEBCACHE_NAME = "WebCacheV01.dat";
    private static final String EDGE_WEBCACHE_PREFIX = "WebCacheV01";
    private static final String EDGE = "Edge";
    private static final String ESE_TOOL_FOLDER = "ESEDatabaseView";
    private static final String EDGE_SPARTAN_NAME = "Spartan.edb";
    private static final String EDGE_HEAD_URL = "url";
    private static final String EDGE_HEAD_ACCESSTIME = "accessedtime";
    private static final String EDGE_KEYWORD_VISIT = "Visited:";

    private static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss a");

    ExtractEdge() throws NoCurrentCaseException {
        moduleTempResultsDir = RAImageIngestModule.getRATempPath(Case.getCurrentCaseThrows(), EDGE)
                + File.separator + "results"; //NON-NLS
    }

    @Messages({
        "ExtractEdge_Module_Name=Microsoft Edge"
    })
    @Override
    protected String getName() {
        return Bundle.ExtractEdge_Module_Name();
    }

    @Messages({
        "ExtractEdge_process_errMsg_unableFindESEViewer=Unable to find ESEDatabaseViewer",
        "ExtractEdge_process_errMsg_errGettingWebCacheFiles=Error trying to retrieving Edge WebCacheV01 file",
        "ExtractEdge_process_errMsg_webcacheFail=Failure processing Microsoft Edge WebCache file"
    })
    @Override
    void process(Content dataSource, IngestJobContext context) {
        this.dataSource = dataSource;
        this.context = context;
        dataFound = false;

        List<AbstractFile> webCacheFiles;
        List<AbstractFile> spartanFiles;
        try {
            webCacheFiles = fetchWebCacheFiles();
            spartanFiles = fetchSpartanFiles(); // For later use with bookmarks
        } catch (TskCoreException ex) {
            this.addErrorMessage(Bundle.ExtractEdge_process_errMsg_errGettingWebCacheFiles());
            logger.log(Level.WARNING, "Error fetching 'WebCacheV01.dat' files for Microsoft Edge", ex); //NON-NLS
            return;
        }

        // No edge files found 
        if (webCacheFiles == null && spartanFiles == null) {
            return;
        }

        dataFound = true;

        if (!PlatformUtil.isWindowsOS()) {
            logger.log(Level.INFO, "Microsoft Edge files found, unable to parse on Non-Windows system"); //NON-NLS
            return;
        }

        final String esedumper = getPathForESEDumper();
        if (esedumper == null) {
            this.addErrorMessage(Bundle.ExtractEdge_process_errMsg_unableFindESEViewer());
            logger.log(Level.SEVERE, "Error finding ESEDatabaseViewer program"); //NON-NLS
            return; //If we cannot find the ESEDatabaseView we cannot proceed
        }

        if (context.dataSourceIngestIsCancelled()) {
            return;
        }

        try {
            this.processWebCache(esedumper, webCacheFiles);
        } catch (IOException ex) {
            this.addErrorMessage(Bundle.ExtractEdge_process_errMsg_webcacheFail());
            logger.log(Level.SEVERE, "Error returned from processWebCach", ex); // NON-NLS
        } catch (TskCoreException tcex) {

        }

        if (context.dataSourceIngestIsCancelled()) {
            return;
        }

        // Bookmarks come from spartan.edb different file
        this.getBookmark(); // Not implemented yet
    }

    void processWebCache(String eseDumperPath, List<AbstractFile> webCachFiles) throws IOException, TskCoreException {

        for (AbstractFile webCacheFile : webCachFiles) {

            //Run the dumper 
            String tempWebCacheFileName = EDGE_WEBCACHE_PREFIX
                    + Integer.toString((int) webCacheFile.getId()) + ".dat"; //NON-NLS
            File tempWebCacheFile = new File(RAImageIngestModule.getRATempPath(currentCase, EDGE)
                    + File.separator + tempWebCacheFileName);

            try {
                ContentUtils.writeToFile(webCacheFile, tempWebCacheFile,
                        context::dataSourceIngestIsCancelled);
            } catch (IOException ex) {
                throw new IOException("Error writingToFile: " + webCacheFile, ex); //NON-NLS
            }

            File resultsDir = new File(moduleTempResultsDir + Integer.toString((int) webCacheFile.getId()));
            resultsDir.mkdirs();
            try {
                executeDumper(eseDumperPath, tempWebCacheFile.getAbsolutePath(),
                        EDGE_WEBCACHE_PREFIX, resultsDir.getAbsolutePath());

                if (context.dataSourceIngestIsCancelled()) {
                    return;
                }

                this.getHistory(webCacheFile, resultsDir); // Not implemented yet

                if (context.dataSourceIngestIsCancelled()) {
                    return;
                }

                this.getCookie(); // Not implemented yet

                if (context.dataSourceIngestIsCancelled()) {
                    return;
                }

                this.getDownload(); // Not implemented yet
            } finally {
                tempWebCacheFile.delete();
                resultsDir.delete();
            }
        }
    }

    /**
     * getHistory searches the files with "container" in the file name for lines
     * with the text "Visited" in them. Note that not all of the container
     * files, if fact most of them do not, have the browser history in them.
     */
    @Messages({
        "ExtractEdge_getHistory_containerFileNotFound=Error while trying to analyze Edge history"
    })
    private void getHistory(AbstractFile origFile, File resultDir) throws TskCoreException {
        File containerFiles[] = resultDir.listFiles((dir, name) -> name.toLowerCase().contains("container"));

        if (containerFiles == null) {
            this.addErrorMessage(Bundle.ExtractEdge_getHistory_containerFileNotFound());
            return;
        }

        for (File file : containerFiles) {
            Scanner fileScanner;
            try {
                fileScanner = new Scanner(new FileInputStream(file.toString()));
            } catch (FileNotFoundException ex) {
                logger.log(Level.WARNING, "Unable to find the ESEDatabaseView file at " + file.getPath(), ex); //NON-NLS
                continue; // If we couldn't open this file, continue to the next file
            }

            Collection<BlackboardArtifact> bbartifacts = new ArrayList<>();

            try {
                List<String> headers = null;
                while (fileScanner.hasNext()) {
                    String line = fileScanner.nextLine();
                    if (headers == null) {
                        headers = Arrays.asList(line.toLowerCase().split(","));
                        continue;
                    }

                    if (line.contains(EDGE_KEYWORD_VISIT)) {
                        BlackboardArtifact b = getHistoryArtifact(origFile, headers, line);
                        if (b != null) {
                            bbartifacts.add(b);
                            this.indexArtifact(b);
                        }
                    } else {
                        // If Visited is not in line than this is probably
                        // not the container file we're looking for, move on
                        break;
                    }
                }
            } finally {
                fileScanner.close();
            }

            if (!bbartifacts.isEmpty()) {
                services.fireModuleDataEvent(new ModuleDataEvent(
                        RecentActivityExtracterModuleFactory.getModuleName(),
                        BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_HISTORY, bbartifacts));
            }
        }
    }

    /**
     * Search for bookmark files and make artifacts.
     */
    private void getBookmark() {

    }

    /**
     * Queries for cookie files and adds artifacts
     */
    private void getCookie() {

    }

    /**
     * Queries for download files and adds artifacts
     */
    private void getDownload() {

    }

    private String getPathForESEDumper() {
        Path path = Paths.get(ESE_TOOL_FOLDER, ESE_TOOL_NAME);
        File eseToolFile = InstalledFileLocator.getDefault().locate(path.toString(),
                ExtractEdge.class.getPackage().getName(), false);
        if (eseToolFile != null) {
            return eseToolFile.getAbsolutePath();
        }

        return null;
    }

    private List<AbstractFile> fetchWebCacheFiles() throws TskCoreException {
        org.sleuthkit.autopsy.casemodule.services.FileManager fileManager
                = currentCase.getServices().getFileManager();
        return fileManager.findFiles(dataSource, EDGE_WEBCACHE_NAME);
    }

    private List<AbstractFile> fetchSpartanFiles() throws TskCoreException {
        org.sleuthkit.autopsy.casemodule.services.FileManager fileManager
                = currentCase.getServices().getFileManager();
        return fileManager.findFiles(dataSource, EDGE_SPARTAN_NAME);
    }

    private void executeDumper(String dumperPath, String inputFilePath,
            String inputFilePrefix, String outputDir) throws IOException {
        final String outputFileFullPath = outputDir + File.separator + inputFilePrefix + ".txt"; //NON-NLS
        final String errFileFullPath = outputDir + File.separator + inputFilePrefix + ".err"; //NON-NLS
        logger.log(Level.INFO, "Writing ESEDatabaseViewer results to: {0}", outputDir); //NON-NLS   

        List<String> commandLine = new ArrayList<>();
        commandLine.add(dumperPath);
        commandLine.add("/table");
        commandLine.add(inputFilePath);
        commandLine.add("*");
        commandLine.add("/scomma");
        commandLine.add(outputDir + "\\" + inputFilePrefix + "_*.csv");

        ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
        processBuilder.redirectOutput(new File(outputFileFullPath));
        processBuilder.redirectError(new File(errFileFullPath));

        ExecUtil.execute(processBuilder, new DataSourceIngestModuleProcessTerminator(context));
    }

    @Messages({
        "ExtractEdge_programName=Microsoft Edge"
    })
    private BlackboardArtifact getHistoryArtifact(AbstractFile origFile, List<String> headers, String line) throws TskCoreException {
        String[] rowSplit = line.split(",");

        int index = headers.indexOf(EDGE_HEAD_URL);
        String urlUserStr = rowSplit[index];

        String[] str = urlUserStr.split("@");
        String user = (str[0].replace(EDGE_KEYWORD_VISIT, "")).trim();
        String url = str[1];

        index = headers.indexOf(EDGE_HEAD_ACCESSTIME);
        String accessTime = rowSplit[index].trim();
        Long ftime = null;
        try {
            Long epochtime = DATE_FORMATTER.parse(accessTime).getTime();
            ftime = epochtime / 1000;
        } catch (ParseException ex) {
            logger.log(Level.WARNING, "The Accessed Time format in history file seems invalid " + accessTime, ex); //NON-NLS
        }

        BlackboardArtifact bbart = origFile.newArtifact(BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_HISTORY);

        bbart.addAttributes(createHistoryAttribute(url, ftime,
                null, null,
                Bundle.ExtractEdge_programName(),
                NetworkUtils.extractDomain(url), user));

        return bbart;
    }

    private Collection<BlackboardAttribute> createHistoryAttribute(String url, Long accessTime,
            String referrer, String title, String programName, String domain, String user) throws TskCoreException {

        Collection<BlackboardAttribute> bbattributes = new ArrayList<>();
        bbattributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_URL,
                RecentActivityExtracterModuleFactory.getModuleName(),
                (url != null) ? url : ""));

        if (accessTime != null) {
            bbattributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED,
                    RecentActivityExtracterModuleFactory.getModuleName(), accessTime));
        }

        bbattributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_REFERRER,
                RecentActivityExtracterModuleFactory.getModuleName(),
                (referrer != null) ? referrer : ""));

        bbattributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_TITLE,
                RecentActivityExtracterModuleFactory.getModuleName(), 
                (title != null) ? title : ""));

        bbattributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PROG_NAME,
                RecentActivityExtracterModuleFactory.getModuleName(), 
                (programName != null) ? programName : ""));

        bbattributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DOMAIN,
                RecentActivityExtracterModuleFactory.getModuleName(), 
                (domain != null) ? domain : ""));

        bbattributes.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_USER_NAME,
                RecentActivityExtracterModuleFactory.getModuleName(), 
                (user != null) ? user : ""));

        return bbattributes;
    }
}

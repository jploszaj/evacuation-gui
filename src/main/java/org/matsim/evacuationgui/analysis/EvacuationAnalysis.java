/* *********************************************************************** *
 * project: org.matsim.*
 * RoadClosuresEditor.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.evacuationgui.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.log4j.Logger;
import org.jfree.data.time.Second;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesDataItem;
import org.json.JSONArray;
import org.json.JSONObject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.StrategyConfigGroup;
import org.matsim.core.events.EventsReaderXMLv1;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.evacuationgui.analysis.control.EventHandler;
import org.matsim.evacuationgui.analysis.control.EventReaderThread;
import org.matsim.evacuationgui.analysis.data.ColorationMode;
import org.matsim.evacuationgui.analysis.data.EventData;
import org.matsim.evacuationgui.analysis.gui.AbstractDataPanel;
import org.matsim.evacuationgui.analysis.gui.KeyPanel;
import org.matsim.evacuationgui.control.Controller;
import org.matsim.evacuationgui.model.AbstractModule;
import org.matsim.evacuationgui.model.AbstractToolBox;
import org.matsim.evacuationgui.model.Constants;
import org.matsim.evacuationgui.model.Constants.Mode;
import org.matsim.evacuationgui.model.imagecontainer.BufferedImageContainer;
import org.matsim.evacuationgui.model.process.*;
import org.matsim.evacuationgui.view.DefaultWindow;
import org.matsim.evacuationgui.view.renderer.GridRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EvacuationAnalysis extends AbstractModule {

    private static final Logger log = Logger.getLogger(EvacuationAnalysis.class);
    private ArrayList<File> eventFiles;
    private File currentEventFile;
    private EventHandler eventHandler;
    private ColorationMode colorationMode = ColorationMode.GREEN_YELLOW_RED;
    private float cellTransparency = 0.6f;
    private int k = 5;
    private GridRenderer gridRenderer;
    private int gridRendererID;
    private AbstractDataPanel graphPanel;
    private KeyPanel keyPanel;
    private Thread readerThread;
    private Mode mode;

    private EAToolBox toolBox;

    private double gridSize = 10;
    private boolean useCellCount = true;

    public EvacuationAnalysis(Controller controller) {
        super(controller.getLocale().moduleEvacuationAnalysis(), Constants.ModuleType.ANALYSIS, controller);

        // disable all layers
        this.processList.add(new DisableLayersProcess(controller));

        // initialize Matsim config
        this.processList.add(new InitMatsimConfigProcess(controller));

        // check if the default render panel is set
        this.processList.add(new InitMainPanelProcess(controller));

        // check if there is already a map viewer running, or just (re)set
        // center position
        this.processList.add(new InitMapLayerProcess(controller));

        // set module listeners
        this.processList.add(new SetModuleListenerProcess(controller, this, new EAEventListener(controller)));

        // add grid renderer
        this.processList.add(new BasicProcess(controller) {
            @Override
            public void start() {
                if (!this.controller.hasGridRenderer()) {
                    gridRenderer = new GridRenderer(controller);
                    gridRendererID = gridRenderer.getId();
                    this.controller.addRenderLayer(gridRenderer);
                }
            }

        });

        // add toolbox
        this.processList.add(new SetToolBoxProcess(controller, getToolBox()));

        // enable all layers
        this.processList.add(new EnableLayersProcess(controller));

        // enable grid renderer
        this.processList.add(new BasicProcess(controller) {
            @Override
            public void start() {
                toolBox.setGridRenderer(gridRenderer);
                readEventsAndSaveToFile();
                readEvents();


                // finally: enable all layers
                controller.enableMapRenderer();

                controller.setToolBoxVisible(true);

                gridRenderer.setEnabled(true);
            }

        });

    }

    public static void main(String[] args) {
        // set up controller and image interface
        final Controller controller = new Controller();
        BufferedImage image = new BufferedImage(width - border * 2, height - border * 2, BufferedImage.TYPE_INT_ARGB);
        BufferedImageContainer imageContainer = new BufferedImageContainer(image, border);
        controller.setImageContainer(imageContainer);

        // inform controller that this module is running stand alone
        controller.setStandAlone(true);

        // instantiate evacuation area selector
        EvacuationAnalysis evacAnalysis = new EvacuationAnalysis(controller);

        // create default window for running this module standalone
        DefaultWindow frame = new DefaultWindow(controller);

        // set parent component to forward the (re)paint event
        controller.setParentComponent(frame);
        controller.setMainPanel(frame.getMainPanel(), true);

        // start the process chain
        evacAnalysis.start();
        frame.requestFocus();

    }

    @Override
    public AbstractToolBox getToolBox() {
        if (this.toolBox == null) {
            this.toolBox = new EAToolBox(this, this.controller);
        }
        return this.toolBox;
    }


    public void runCalculation() {
        try {
            this.controller.getParentComponent().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

            if (currentEventFile != null) {
                readEvents();

                this.controller.paintLayers();

            }
        } finally {
            this.controller.getParentComponent().setCursor(Cursor.getDefaultCursor());
        }
    }

    public void setCellTransparency(float cellTransparency) {
        this.cellTransparency = cellTransparency;
    }

    public static void sortFileList(ArrayList<File> fileList) {
        // Custom comparator to compare files based on the numbers in their names
        Comparator<File> fileComparator = new Comparator<File>() {
            @Override
            public int compare(File file1, File file2) {
                return extractNumber(file1.getName()) - extractNumber(file2.getName());
            }
        };

        // Sorting the file list using the custom comparator
        fileList.sort(fileComparator);
    }

    private static int extractNumber(String fileName) {
        // Regular expression pattern to extract numbers from file names
        Pattern pattern = Pattern.compile("(\\d+)\\.events\\.xml\\.gz");
        Matcher matcher = pattern.matcher(fileName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return Integer.MAX_VALUE; // Return max value if no number found
    }

    public void readEvents() {
        if (currentEventFile == null) {
            var fileList = getAvailableEventFiles(this.controller.getIterationsOutputDirectory());
            sortFileList(fileList);
            eventFiles = fileList;
            currentEventFile = eventFiles.get(0);

            // check if empty
            if (eventFiles.isEmpty()) {
                JOptionPane.showMessageDialog(this.controller.getParentComponent(), "Could not find any event files", "Event files unavailable", JOptionPane.ERROR_MESSAGE);
                return;
            } else {
//                this.toolBox.getOpenOTFVisBtn().setEnabled(true);
            }
            ((EAToolBox) getToolBox()).setEventFileItems(eventFiles);
        }
        if ((graphPanel == null) || (keyPanel == null)) {
            graphPanel = ((EAToolBox) getToolBox()).getGraphPanel();
            keyPanel = ((EAToolBox) getToolBox()).getKeyPanel();
        }

        // run event reader
        runEventReader(currentEventFile);

        // get data from eventhandler (if not null)
        if (eventHandler != null) {
            eventHandler.setColorationMode(this.colorationMode);
            eventHandler.setTransparency(this.cellTransparency);
            eventHandler.setK(k);

            // get data
            EventData data = eventHandler.getData();

            // update data in both the map viewer and the graphs

            this.controller.setEventData(data);

            graphPanel.updateData(data);
            keyPanel.updateData(data);
        }

        ((EAToolBox) getToolBox()).setFirstLoad(false);

    }

    public void readEventsAndSaveToFile() {
        var fileList = getAvailableEventFiles(this.controller.getIterationsOutputDirectory());
        sortFileList(fileList);

        JSONArray eventDataList = new JSONArray();

        // Read routing algorithm type from routingAlgorithmType.json
        RoutingAlgorithmTypeFile routingAlgorithmType1 = getRoutingAlgorithmType();
        String routingAlgorithmType = routingAlgorithmType1.routingAlgorithmType;
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        LocalDateTime start;
        try {
            start = mapper.readValue(routingAlgorithmType1.start, LocalDateTime.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        LocalDateTime now = LocalDateTime.now();

        Duration duration = Duration.between(now, start);

        JSONObject acoConfig = null;
        if (routingAlgorithmType.equals("ACO")) {
            // Read ACO configuration from aco-configuration.json
            acoConfig = readACOParametersFromFile();
        }

        // Create a common JSON object for routing algorithm type and ACO configuration
        JSONObject commonData = new JSONObject();
        commonData.put("time", duration);

        commonData.put("routing_algorithm_type", routingAlgorithmType);

        commonData.put("runId", routingAlgorithmType1.runId);
        commonData.put("iterations", fileList.size());
        if (acoConfig != null) {
            commonData.put("aco_configuration", acoConfig);
        }

        // Add common data to root object
        JSONObject root = new JSONObject();
        root.put("common_data", commonData);
        root.put("events", eventDataList);

        double bestEvacuationTime = Double.MAX_VALUE;
        double bestClearingTime = Double.MAX_VALUE;
        double bestGraphTime = Double.MAX_VALUE;
        String bestEvacuationFileName = "";
        String bestClearingFileName = "";
        String bestGraphTimeFileName = "";

        for (File file : fileList) {
            // run event reader
            var eventHandlerTmp = runEventReaderForWritingAnalysisToFile(file);

            // get data from event handler (if not null)
            if (eventHandlerTmp != null) {
                eventHandlerTmp.setColorationMode(this.colorationMode);
                eventHandlerTmp.setTransparency(this.cellTransparency);
                eventHandlerTmp.setK(k);

                EventData data = eventHandlerTmp.getData();

                List<Tuple<Double, Integer>> arrivalTimes = data.getArrivalTimes();
                int arrivalTimeCount = arrivalTimes.size();


                double[] xs = new double[arrivalTimeCount];
                double[] ys = new double[arrivalTimeCount];

                TimeSeries timeSeries = new TimeSeries("evacuation time");


                for (int i = 0; i < arrivalTimeCount; i++) {
                    xs[i] = 1000 * 60 * 60 * 23 + arrivalTimes.get(i).getFirst() * 1000;
                    ys[i] = arrivalTimes.get(i).getSecond() / data.getSampleSize();
                    timeSeries.add(new Second(new Date((long) xs[i])), ys[i]);
                }


                TimeSeriesDataItem lastItem = timeSeries.getDataItem(timeSeries.getItems().size() - 1);
                Date date = lastItem.getPeriod().getEnd();
                var lastItemSeconds = date.getHours() * 3600 + date.getMinutes() * 60 + date.getSeconds();

                // Get evacuation time
                LinkedList<Tuple<Id<Link>, Double>> evacuationClusters = data.getClusters(Mode.EVACUATION);
                double evacuationTime = evacuationClusters.get(evacuationClusters.size() - 1).getSecond();

                // Get clearing time
                LinkedList<Tuple<Id<Link>, Double>> clearingClusters = data.getClusters(Mode.CLEARING);
                double clearingTime = clearingClusters.get(clearingClusters.size() - 1).getSecond();

                if (evacuationTime < bestEvacuationTime) {
                    bestEvacuationTime = evacuationTime;
                    bestEvacuationFileName = file.getName();
                }
                if (clearingTime < bestClearingTime) {
                    bestClearingTime = clearingTime;
                    bestClearingFileName = file.getName();
                }
                if (lastItemSeconds < bestGraphTime) {
                    bestGraphTime = lastItemSeconds;
                    bestGraphTimeFileName = file.getName();
                }


                // Create JSON object for this file's data
                JSONObject eventData = new JSONObject();
                eventData.put("file_name", file);
                eventData.put("evacuation_time", getTime(evacuationTime, Constants.Unit.TIME));
                eventData.put("clearing_time", getTime(clearingTime, Constants.Unit.TIME));
                eventData.put("graph_evacuation_time", getTime(lastItemSeconds, Constants.Unit.TIME));

                eventDataList.put(eventData);
            }
        }

        commonData.put("best_evacuation_time", getTime(bestEvacuationTime, Constants.Unit.TIME));
        commonData.put("best_clearing_time", getTime(bestClearingTime, Constants.Unit.TIME));
        commonData.put("best_graph_evacuation_time", getTime(bestGraphTime, Constants.Unit.TIME));
        commonData.put("population", this.controller.getScenario().getPopulation().getPersons().size());
        commonData.put("file_name_of_best_evacuation", bestEvacuationFileName);
        commonData.put("file_name_of_best_clearing", bestClearingFileName);
        commonData.put("file_name_of_best_graph_evacuation_time", bestGraphTimeFileName);
        var strategyReplanning = this.controller.getScenario().getConfig().strategy().getParameterSets().get("strategysettings");
        var listStrategyReplanning = strategyReplanning.stream().toList();
        if (listStrategyReplanning.size() > 2) {
            throw new NotImplementedException("not implemented yet");
        }
        for (int i = 0; i < listStrategyReplanning.size(); i++) {

            var name = i == 0 ? "RandomPlanSelector_ReRoute" : "ExpBetaPlanChanger";
            commonData.put(name, ((StrategyConfigGroup.StrategySettings) listStrategyReplanning.get(i)).getWeight());

        }

        String replanning = "-weight";
        if (commonData.has("RandomPlanSelector_ReRoute")) {
            replanning += "-rer" + commonData.get("RandomPlanSelector_ReRoute");
        }
        if (commonData.has("ExpBetaPlanChanger")) {
            replanning += "-exp" + commonData.get("ExpBetaPlanChanger");
        }


        var outputFilename = "output" + routingAlgorithmType.toLowerCase() + "-i" + fileList.size() + replanning + ".json";

        if (acoConfig != null) {
            var heuristicType = acoConfig.get("heuristicType").equals("TRAVEL_COST") ? "t" : "l";
            var acoType = acoConfig.get("acoType").equals("VARIANT_1") ? "clssc" : "cstm";
            outputFilename = "output-" + routingAlgorithmType.toLowerCase() + "-i" + fileList.size() + replanning + "-t" + acoType + "-a" + acoConfig.get("alpha") + "-b" + acoConfig.get("beta") + "-p" + acoConfig.get("pheromoneConstant") + "-e" + acoConfig.get("evaporationRate") + "-q" + acoConfig.get("q") + "-h" + heuristicType + ".json";
        }

        String outputPath = "tests/";
        int iterateNumber = 0;
        while (true) {
            String filename = iterateNumber == 0 ? outputFilename : outputFilename.replace(".json", "_" + iterateNumber + ".json");



            File outputFile = new File(outputPath + filename);
            if (isRunIdFileAlreadyExists(outputFile.getPath(), routingAlgorithmType1.runId)) {
                System.out.println("Run id stats already exists: "  + outputFile.getPath());
                break;
            }

            if (!outputFile.exists()) {
                try (FileWriter fileWriter = new FileWriter(outputFile)) {
                    fileWriter.write(root.toString());
                    System.out.println("Data saved to " + outputFile.getPath());
                    super.setTitle(controller.getLocale().moduleEvacuationAnalysis() + " with stat file: " + outputFile.getPath());

                    break; // Exit the loop once a unique filename is found and the file is written
                } catch (IOException e) {
                    System.err.println("Error writing JSON to file: " + e.getMessage());
                    break; // Exit the loop if there's an error
                }
            }

            iterateNumber++;
        }
    }

    public static String getTime(double value, Constants.Unit unit) {
        if (unit.equals(Constants.Unit.PEOPLE))
            throw new NotImplementedException("Not implemented");

        if (value < 0d)
            return "";

        int hours = (int) (value / 3600);
        int minutes = (int) ((value % 3600) / 60);
        int seconds = (int) (value % 60);

        return String.format("%dh%02dm%02ds", hours, minutes, seconds);
    }

    public record RoutingAlgorithmTypeFile(String routingAlgorithmType, String runId, String start){};

    private RoutingAlgorithmTypeFile getRoutingAlgorithmType() {
        try {
            // Read routing algorithm type from routingAlgorithmType.json
            BufferedReader reader = new BufferedReader(new FileReader("/home/jan/aaevacuation-config/routingAlgorithmType.json"));
            String line = reader.readLine();
            reader.close();
            JSONObject jsonObject = new JSONObject(line);
            var routingAlgorithmTypeFileRecord = new RoutingAlgorithmTypeFile(jsonObject.getString("routingAlgorithmType"), jsonObject.getString("runId"), jsonObject.getString("start"));
            return routingAlgorithmTypeFileRecord;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean isRunIdFileAlreadyExists(String filePath, String runId) {
        try {
            // Read routing algorithm type from routingAlgorithmType.json
            File configFile = new File(filePath);

            if (configFile.exists() && !configFile.isDirectory()) {
                // Parse the JSON file if it exists
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                reader.close();
                var json = new JSONObject(content.toString());
                return json.getJSONObject("common_data").getString("runId").equals(runId);
            } else {
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }



    private JSONObject readACOParametersFromFile() {
        JSONObject defaultParameters = getDefaultACOParameters(); // Get default parameters
        try {
            // Path to the JSON file
            String filePath = "/home/jan/aaevacuation-config/aco-configuration.json";
            File configFile = new File(filePath);

            if (configFile.exists() && !configFile.isDirectory()) {
                // Parse the JSON file if it exists
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                reader.close();
                return new JSONObject(content.toString());
            } else {
                // Return default parameters if the file doesn't exist
                return defaultParameters;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return defaultParameters; // Return default parameters in case of any exception
        }
    }

    // Define default parameters
    private JSONObject getDefaultACOParameters() {
        JSONObject defaultParameters = new JSONObject();
        defaultParameters.put("numberOfAnts", 20); // Default value for numberOfAnts
        defaultParameters.put("pheromoneConstant", 0.1); // Default value for pheromoneConstant
        defaultParameters.put("alpha", 1.0); // Default value for alpha
        defaultParameters.put("beta", 2.0); // Default value for beta
        defaultParameters.put("evaporationRate", 0.5); // Default value for evaporationRate
        defaultParameters.put("q", 1.0); // Default value for Q
        defaultParameters.put("heuristicType", "TRAVEL_COST");
        defaultParameters.put("runId", "default");

        return defaultParameters;
    }


    public EventHandler runEventReaderForWritingAnalysisToFile(File eventFile) {
        EventsManager e = EventsUtils.createEventsManager();
        EventsReaderXMLv1 reader = new EventsReaderXMLv1(e);
        var readerThread = new Thread(new EventReaderThread(reader, eventFile.toString()), "readerthread");
        var eventHandler = new EventHandler(useCellCount, eventFile.getName(), this.controller.getScenario(), this.gridSize, readerThread);
        e.addHandler(eventHandler);
        readerThread.run();

        return eventHandler;
    }


    public void runEventReader(File eventFile) {

        this.eventHandler = null;
        EventsManager e = EventsUtils.createEventsManager();
        EventsReaderXMLv1 reader = new EventsReaderXMLv1(e);
        this.readerThread = new Thread(new EventReaderThread(reader, eventFile.toString()), "readerthread");
        this.eventHandler = new EventHandler(useCellCount, eventFile.getName(), this.controller.getScenario(), this.gridSize, this.readerThread);
        e.addHandler(this.eventHandler);
        this.readerThread.run();

    }

    public File getEventPathFromName(String selectedItem) {
        for (File eventFile : eventFiles) {
            if (eventFile.getName().equals(selectedItem)) {
                return eventFile;
            }
        }
        return null;
    }

    public EventHandler getEventHandler() {
        return eventHandler;
    }

    public File getCurrentEventFile() {
        return this.currentEventFile;
    }

    public void setCurrentEventFile(File currentEventFile) {
        this.currentEventFile = currentEventFile;
    }

    public GridRenderer getGridRenderer() {
        return gridRenderer;
    }

    public int getGridRendererID() {
        return gridRendererID;
    }

    public ArrayList<File> getAvailableEventFiles(String dirString) {
        File dir = new File(dirString);
        Stack<File> directoriesToScan = new Stack<File>();
        ArrayList<File> files = new ArrayList<File>();

        directoriesToScan.add(dir);

        while (!directoriesToScan.isEmpty()) {
            File currentDir = directoriesToScan.pop();
            File[] filesToCheck = currentDir.listFiles(new EventFileFilter());

            if (filesToCheck.length > 0) {

                for (File currentFile : filesToCheck) {
                    if (currentFile.isDirectory()) {
                        directoriesToScan.push(currentFile);
                    } else {
                        if (!files.contains(currentFile)) {
                            files.add(currentFile);
                        }
                    }
                }
            }

        }

        return files;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;

        this.gridRenderer.setMode(mode);
        if (keyPanel != null) {
            keyPanel.setMode(mode);
        }

    }

    public void setGraphPanel(AbstractDataPanel graphPanel) {
        this.graphPanel = graphPanel;

    }

    public void setKeyPanel(KeyPanel keyPanel) {
        this.keyPanel = keyPanel;

    }

    public void setGridSize(double gridSize) {
        this.gridSize = gridSize;

    }

    class EventFileFilter implements java.io.FileFilter {

        @Override
        public boolean accept(File f) {
            if (f.isDirectory()) {
                return true;
            }
            return f.getName().endsWith(".events.xml.gz");
        }
    }

}

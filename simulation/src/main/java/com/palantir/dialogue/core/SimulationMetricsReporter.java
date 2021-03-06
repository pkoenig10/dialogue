/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.dialogue.core;

import com.codahale.metrics.Counting;
import com.codahale.metrics.Gauge;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import com.palantir.tritium.metrics.registry.MetricName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a metric reporter, all hooked up to {@link Simulation#clock()}.
 * Capable of reporting PNGs (although this is slow).
 */
final class SimulationMetricsReporter {
    private static final Logger log = LoggerFactory.getLogger(SimulationMetricsReporter.class);

    private final Simulation simulation;

    // each of these is a named column
    private final LoadingCache<String, List<Double>> measurements =
            Caffeine.newBuilder().build(name -> new ArrayList<>(Collections.nCopies(numMeasurements(), 0d)));

    private static final String X_AXIS = "time_sec";

    SimulationMetricsReporter(Simulation simulation) {
        this.simulation = simulation;
    }

    private int numMeasurements() {
        List<Double> xAxis = measurements.getIfPresent(X_AXIS);
        return (xAxis != null) ? xAxis.size() : 0;
    }

    public void report() {
        simulation.taggedMetrics().forEachMetric((metricName, metric) -> {
            String name = asString(metricName);

            if (metric instanceof Counting) { // includes meters too!
                measurements.get(name + ".count").add((double) ((Counting) metric).getCount());
                return;
            }

            if (metric instanceof Gauge<?>) {
                Object value = ((Gauge<?>) metric).getValue();
                Preconditions.checkState(
                        value instanceof Number,
                        "Gauges must produce numbers",
                        SafeArg.of("metric", metricName),
                        SafeArg.of("value", value));
                measurements.get(name + ".count").add(((Number) value).doubleValue());
                return;
            }

            log.error("Unknown metric type {} {}", metricName, metric);
        });

        long nanos = simulation.clock().read();
        double seconds = TimeUnit.MILLISECONDS.convert(nanos, TimeUnit.NANOSECONDS) / 1000d;
        measurements.get(X_AXIS).add(seconds);
    }

    public XYChart chart(Pattern metricNameRegex) {
        XYChart chart =
                new XYChartBuilder().width(800).height(600).xAxisTitle(X_AXIS).build();

        // if we render too many samples, it just ends up looking like a wall of colour
        int granularity = chart.getWidth() / 3;

        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setMarkerSize(3);
        chart.getStyler().setYAxisLabelAlignment(Styler.TextAlignment.Right);
        chart.getStyler().setPlotMargin(0);
        chart.getStyler().setPlotContentSize(.95);
        chart.getStyler().setToolTipsEnabled(true);
        chart.getStyler().setToolTipsAlwaysVisible(true);

        Map<String, List<Double>> map = measurements.asMap();
        double[] xAxis = reduceGranularity(
                granularity, map.get(X_AXIS).stream().mapToDouble(d -> d).toArray());
        List<String> columns = map.keySet().stream()
                .filter(name -> !name.equals(X_AXIS))
                .filter(metricNameRegex.asPredicate())
                .sorted()
                .collect(Collectors.toList());
        String[] nullToolTips = Collections.nCopies(xAxis.length, null).toArray(new String[] {});

        for (String column : columns) {
            double[] series = reduceGranularity(
                    granularity, map.get(column).stream().mapToDouble(d -> d).toArray());
            Preconditions.checkState(
                    series.length == xAxis.length,
                    "Series must all be same length",
                    SafeArg.of("column", column),
                    SafeArg.of("xaxis", xAxis.length),
                    SafeArg.of("length", series.length));
            chart.addSeries(column, xAxis, series).setToolTips(nullToolTips);
        }

        if (!simulation.events().getEvents().isEmpty()) {
            double[] eventXs = simulation.events().getEvents().keySet().stream()
                    .mapToDouble(nanos -> TimeUnit.MILLISECONDS.convert(nanos, TimeUnit.NANOSECONDS) / 1000d)
                    .toArray();
            double[] eventYs = new double[eventXs.length];
            String[] strings = simulation.events().getEvents().values().stream().toArray(String[]::new);
            XYSeries what = chart.addSeries(" ", eventXs, eventYs);
            what.setToolTips(strings);
            what.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
        }

        return chart;
    }

    /**
     * If we render too many dots on the graph, it ends up looking like a wall of colour. Doing one dot per pixel of
     * width is also crap.
     */
    private static double[] reduceGranularity(int maxSamples, double[] rawSamples) {
        if (rawSamples.length < maxSamples) {
            return rawSamples;
        }

        double[] halfGranularity = new double[rawSamples.length / 2];
        for (int i = 0; i < halfGranularity.length; i++) {
            halfGranularity[i] = (rawSamples[2 * i] + rawSamples[2 * i + 1]) / 2d;
        }

        return reduceGranularity(maxSamples, halfGranularity);
    }

    private static String asString(MetricName metricName) {
        return metricName.safeTags().values().stream().map(v -> '[' + v + "] ").collect(Collectors.joining())
                + metricName.safeName();
    }

    public static void png(String file, XYChart... charts) throws IOException {
        int rows = charts.length;
        int cols = 1;
        BitmapEncoder.saveBitmap(ImmutableList.copyOf(charts), rows, cols, file, BitmapEncoder.BitmapFormat.PNG);
    }
}

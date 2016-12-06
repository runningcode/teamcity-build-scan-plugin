package nu.studer.teamcity.buildscan.internal.slack;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import nu.studer.teamcity.buildscan.BuildScanReference;
import nu.studer.teamcity.buildscan.BuildScanReferences;
import nu.studer.teamcity.buildscan.ExternalIntegration;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

final class SlackIntegration implements ExternalIntegration {

    private static final Logger LOGGER = Logger.getLogger("jetbrains.buildServer.BUILDSCAN");

    private final SlackPayloadFactory payloadFactory = SlackPayloadFactory.create();
    private final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(10));

    @SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "ConstantConditions"})
    @Override
    @NotNull
    public Optional<Future> handle(@NotNull BuildScanReferences buildScans, @NotNull Map<String, String> params) {
        if (buildScans.isEmpty()) {
            return Optional.empty();
        }

        URL webhookUrlString = getWebhookURL(params);
        if (webhookUrlString == null) {
            return Optional.empty();
        }

        List<ListenableFuture<Optional<BuildScanPayload>>> payloadAllFutures = retrieveBuildScansAsync(buildScans);
        ListenableFuture<List<Optional<BuildScanPayload>>> payloadSucFutures = Futures.successfulAsList(payloadAllFutures);
        ListenableFuture<Map<String, BuildScanPayload>> payloadPerBuildScanFutures = Futures.transform(payloadSucFutures, ps -> ps
            .stream()
            .filter(Objects::nonNull)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(toMap(buildScanPayload -> buildScanPayload.data.publicId, Function.identity())));

        ListenableFuture<Void> notifySlackFuture = Futures.transformAsync(payloadPerBuildScanFutures, payloadPerBuildScan ->
            notifySlackAsync(buildScans, params, payloadPerBuildScan, webhookUrlString)
        );

        return Optional.of(notifySlackFuture);
    }

    private static URL getWebhookURL(Map<String, String> params) {
        String webhookUrlString = params.get("BUILD_SCAN_SLACK_WEBHOOK_URL");
        if (webhookUrlString == null) {
            return null;
        }

        try {
            return new URL(webhookUrlString);
        } catch (MalformedURLException e) {
            LOGGER.error("Invalid Slack webhook URL: " + webhookUrlString, e);
            return null;
        }
    }

    private ListenableFuture<Void> notifySlackAsync(@NotNull BuildScanReferences buildScans, Map<String, String> params, Map<String, BuildScanPayload> buildScanPayloads, URL webhookUrl) {
        ListenableFuture<Void> future = executor.submit(() -> notifySlack(buildScans, params, buildScanPayloads, webhookUrl));
        Futures.addCallback(future, new LoggingCallback("Notifying Slack via webhook"));
        return future;
    }

    private Void notifySlack(@NotNull BuildScanReferences buildScans, Map<String, String> params, Map<String, BuildScanPayload> buildScanPayloads, URL webhookUrl) throws IOException {
        LOGGER.info("Notifying Slack via webhook: " + webhookUrl);
        SlackHttpNotifier notifier = SlackHttpNotifier.forWebhook(webhookUrl);
        notifier.notify(payloadFactory.from(buildScans, buildScanPayloads, params));
        return null;
    }

    private List<ListenableFuture<Optional<BuildScanPayload>>> retrieveBuildScansAsync(BuildScanReferences buildScans) {
        return buildScans.all().stream().map(s -> {
            ListenableFuture<Optional<BuildScanPayload>> future = executor.submit(() -> retrieveBuildScan(s));
            Futures.addCallback(future, new LoggingCallback("Retrieving build scan data"));
            return future;
        }).collect(toList());
    }

    private Optional<BuildScanPayload> retrieveBuildScan(BuildScanReference buildScan) throws IOException {
        LOGGER.info("Retrieving build scan data: " + buildScan.getUrl());
        BuildScanHttpRetriever retriever = BuildScanHttpRetriever.forUrl(toScanDataUrl(buildScan));
        BuildScanPayload payload = retriever.retrieve();
        return Optional.of(payload).filter(p -> p.state.equals("complete"));
    }

    @NotNull
    private static URL toScanDataUrl(BuildScanReference buildScan) throws MalformedURLException {
        return new URL(buildScan.getUrl().replace("/s/", "/scan-data/"));
    }

    @SuppressWarnings("unused")
    void shutdown() {
        LOGGER.info("Terminating Slack executor");
        try {
            executor.shutdown();
            executor.awaitTermination(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.error("Error awaiting Slack executor termination", e);
        }
    }

    private static final class LoggingCallback implements FutureCallback<Object> {

        private final String action;

        private LoggingCallback(String action) {
            this.action = action;
        }

        @Override
        public void onSuccess(@Nullable Object result) {
            LOGGER.info(action + " succeeded");
        }

        @Override
        public void onFailure(@NotNull Throwable t) {
            LOGGER.error(action + " failed", t);
        }

    }

}
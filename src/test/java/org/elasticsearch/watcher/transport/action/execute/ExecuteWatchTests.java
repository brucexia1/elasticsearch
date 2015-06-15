/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transport.action.execute;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.elasticsearch.action.ActionRequestValidationException;
import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.watcher.actions.ActionStatus;
import org.elasticsearch.watcher.client.WatcherClient;
import org.elasticsearch.watcher.execution.ActionExecutionMode;
import org.elasticsearch.watcher.execution.Wid;
import org.elasticsearch.watcher.support.WatcherDateTimeUtils;
import org.elasticsearch.watcher.support.xcontent.XContentSource;
import org.elasticsearch.watcher.test.AbstractWatcherIntegrationTests;
import org.elasticsearch.watcher.transport.actions.ack.AckWatchRequestBuilder;
import org.elasticsearch.watcher.transport.actions.ack.AckWatchResponse;
import org.elasticsearch.watcher.transport.actions.execute.ExecuteWatchRequestBuilder;
import org.elasticsearch.watcher.transport.actions.execute.ExecuteWatchResponse;
import org.elasticsearch.watcher.transport.actions.get.GetWatchResponse;
import org.elasticsearch.watcher.transport.actions.put.PutWatchResponse;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.watcher.watch.WatchStatus;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.watcher.actions.ActionBuilders.loggingAction;
import static org.elasticsearch.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.watcher.condition.ConditionBuilders.alwaysCondition;
import static org.elasticsearch.watcher.condition.ConditionBuilders.neverCondition;
import static org.elasticsearch.watcher.input.InputBuilders.simpleInput;
import static org.elasticsearch.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.watcher.trigger.schedule.Schedules.cron;
import static org.elasticsearch.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 *
 */
public class ExecuteWatchTests extends AbstractWatcherIntegrationTests {


    @Test(expected = ActionRequestValidationException.class)
    public void testExecute_InvalidWatchId() throws Exception {
        DateTime now = DateTime.now(DateTimeZone.UTC);
        watcherClient().prepareExecuteWatch("id with whitespaces")
                .setTriggerEvent(new ScheduleTriggerEvent(now, now))
                .get();
    }

    @Test(expected = ActionRequestValidationException.class)
    public void testExecute_InvalidActionId() throws Exception {
        DateTime now = DateTime.now(DateTimeZone.UTC);
        watcherClient().prepareExecuteWatch("_id")
                .setTriggerEvent(new ScheduleTriggerEvent(now, now))
                .setActionMode("id with whitespaces", randomFrom(ActionExecutionMode.values()))
                .get();
    }

    @Test
    public void testExecute_AllDefaults() throws Exception {
        WatcherClient watcherClient = watcherClient();

        PutWatchResponse putWatchResponse = watcherClient.preparePutWatch()
                .setId("_id")
                .setSource(watchBuilder()
                        .trigger(schedule(cron("0/5 * * * * ? 2099")))
                        .input(simpleInput("foo", "bar"))
                        .condition(alwaysCondition())
                        .addAction("log", loggingAction("_text")))
                .get();

        assertThat(putWatchResponse.isCreated(), is(true));

        ExecuteWatchResponse response = watcherClient.prepareExecuteWatch("_id").get();
        assertThat(response, notNullValue());
        assertThat(response.getRecordId(), notNullValue());
        Wid wid = new Wid(response.getRecordId());
        assertThat(wid.watchId(), is("_id"));

        XContentSource record = response.getRecordSource();
        assertValue(record, "watch_id", is("_id"));
        assertValue(record, "trigger_event.type", is("manual"));
        assertValue(record, "trigger_event.triggered_time", notNullValue());
        String triggeredTime = record.getValue("trigger_event.triggered_time");
        assertValue(record, "trigger_event.manual.schedule.scheduled_time", is(triggeredTime));
        assertValue(record, "state", is("executed"));
        assertValue(record, "input.simple.foo", is("bar"));
        assertValue(record, "condition.always", notNullValue());
        assertValue(record, "result.execution_time", notNullValue());
        assertValue(record, "result.execution_duration", notNullValue());
        assertValue(record, "result.input.type", is("simple"));
        assertValue(record, "result.input.payload.foo", is("bar"));
        assertValue(record, "result.condition.type", is("always"));
        assertValue(record, "result.condition.met", is(true));
        assertValue(record, "result.actions.0.id", is("log"));
        assertValue(record, "result.actions.0.type", is("logging"));
        assertValue(record, "result.actions.0.status", is("success"));
        assertValue(record, "result.actions.0.logging.logged_text", is("_text"));
    }

    @Test @Repeat(iterations = 5)
    public void testExecute_CustomTriggerData() throws Exception {
        WatcherClient watcherClient = watcherClient();

        PutWatchResponse putWatchResponse = watcherClient.preparePutWatch()
                .setId("_id")
                .setSource(watchBuilder()
                        .trigger(schedule(cron("0/5 * * * * ? 2099")))
                        .input(simpleInput("foo", "bar"))
                        .condition(alwaysCondition())
                        .addAction("log", loggingAction("_text")))
                .get();

        assertThat(putWatchResponse.isCreated(), is(true));

        DateTime triggeredTime = DateTime.now(DateTimeZone.UTC);
        DateTime scheduledTime = randomBoolean() ? triggeredTime.minusDays(1) : triggeredTime;

        ExecuteWatchRequestBuilder requestBuilder = watcherClient.prepareExecuteWatch("_id");
        if (randomBoolean()) {
            Map<String, Object> data = new HashMap<>();
            data.put("triggered_time", WatcherDateTimeUtils.formatDate(triggeredTime));
            if (scheduledTime != triggeredTime) {
                data.put("scheduled_time", WatcherDateTimeUtils.formatDate(scheduledTime));
            }
            requestBuilder.setTriggerData(data);
        } else {
            ScheduleTriggerEvent event = new ScheduleTriggerEvent(triggeredTime, scheduledTime);
            requestBuilder.setTriggerEvent(event);
        }
        ExecuteWatchResponse response = requestBuilder.get();

        assertThat(response, notNullValue());
        assertThat(response.getRecordId(), notNullValue());
        Wid wid = new Wid(response.getRecordId());
        assertThat(wid.watchId(), is("_id"));

        XContentSource record = response.getRecordSource();
        assertValue(record, "watch_id", is("_id"));
        assertValue(record, "trigger_event.type", is("manual"));
        assertValue(record, "trigger_event.triggered_time", is(WatcherDateTimeUtils.formatDate(triggeredTime)));
        assertValue(record, "trigger_event.manual.schedule.scheduled_time", is(WatcherDateTimeUtils.formatDate(scheduledTime)));
        assertValue(record, "state", is("executed"));
        assertValue(record, "input.simple.foo", is("bar"));
        assertValue(record, "condition.always", notNullValue());
        assertValue(record, "result.execution_time", notNullValue());
        assertValue(record, "result.execution_duration", notNullValue());
        assertValue(record, "result.input.type", is("simple"));
        assertValue(record, "result.input.payload.foo", is("bar"));
        assertValue(record, "result.condition.type", is("always"));
        assertValue(record, "result.condition.met", is(true));
        assertValue(record, "result.actions.0.id", is("log"));
        assertValue(record, "result.actions.0.type", is("logging"));
        assertValue(record, "result.actions.0.status", is("success"));
        assertValue(record, "result.actions.0.logging.logged_text", is("_text"));
    }

    @Test
    public void testExecute_AlternativeInput() throws Exception {
        WatcherClient watcherClient = watcherClient();

        PutWatchResponse putWatchResponse = watcherClient.preparePutWatch()
                .setId("_id")
                .setSource(watchBuilder()
                        .trigger(schedule(cron("0/5 * * * * ? 2099")))
                        .input(simpleInput("foo", "bar"))
                        .condition(alwaysCondition())
                        .addAction("log", loggingAction("_text")))
                .get();

        assertThat(putWatchResponse.isCreated(), is(true));

        ExecuteWatchResponse response = watcherClient.prepareExecuteWatch("_id")
                .setAlternativeInput(ImmutableMap.<String, Object>of("foo1", "bar1"))
                .get();
        assertThat(response, notNullValue());
        assertThat(response.getRecordId(), notNullValue());
        Wid wid = new Wid(response.getRecordId());
        assertThat(wid.watchId(), is("_id"));

        XContentSource record = response.getRecordSource();
        assertValue(record, "watch_id", is("_id"));
        assertValue(record, "trigger_event.type", is("manual"));
        assertValue(record, "trigger_event.triggered_time", notNullValue());
        String triggeredTime = record.getValue("trigger_event.triggered_time");
        assertValue(record, "trigger_event.manual.schedule.scheduled_time", is(triggeredTime));
        assertValue(record, "state", is("executed"));
        assertValue(record, "input.simple.foo", is("bar")); // this is the original input
        assertValue(record, "condition.always", notNullValue());
        assertValue(record, "result.execution_time", notNullValue());
        assertValue(record, "result.execution_duration", notNullValue());
        assertValue(record, "result.input.type", is("simple"));
        assertValue(record, "result.input.payload.foo1", is("bar1")); // this is the alternative one
        assertValue(record, "result.condition.type", is("always"));
        assertValue(record, "result.condition.met", is(true));
        assertValue(record, "result.actions.0.id", is("log"));
        assertValue(record, "result.actions.0.type", is("logging"));
        assertValue(record, "result.actions.0.status", is("success"));
        assertValue(record, "result.actions.0.logging.logged_text", is("_text"));
    }

    @Test
    public void testExecute_IgnoreCondition() throws Exception {
        WatcherClient watcherClient = watcherClient();

        PutWatchResponse putWatchResponse = watcherClient.preparePutWatch()
                .setId("_id")
                .setSource(watchBuilder()
                        .trigger(schedule(cron("0/5 * * * * ? 2099")))
                        .input(simpleInput("foo", "bar"))
                        .condition(neverCondition())
                        .addAction("log", loggingAction("_text")))
                .get();

        assertThat(putWatchResponse.isCreated(), is(true));

        ExecuteWatchResponse response = watcherClient.prepareExecuteWatch("_id")
                .setIgnoreCondition(true)
                .get();

        assertThat(response, notNullValue());
        assertThat(response.getRecordId(), notNullValue());
        Wid wid = new Wid(response.getRecordId());
        assertThat(wid.watchId(), is("_id"));

        XContentSource record = response.getRecordSource();
        assertValue(record, "watch_id", is("_id"));
        assertValue(record, "trigger_event.type", is("manual"));
        assertValue(record, "trigger_event.triggered_time", notNullValue());
        String triggeredTime = record.getValue("trigger_event.triggered_time");
        assertValue(record, "trigger_event.manual.schedule.scheduled_time", is(triggeredTime));
        assertValue(record, "state", is("executed"));
        assertValue(record, "input.simple.foo", is("bar"));
        assertValue(record, "condition.never", notNullValue()); // the original condition
        assertValue(record, "result.execution_time", notNullValue());
        assertValue(record, "result.execution_duration", notNullValue());
        assertValue(record, "result.input.type", is("simple"));
        assertValue(record, "result.input.payload.foo", is("bar"));
        assertValue(record, "result.condition.type", is("always")); // when ignored, the condition is replaced with "always"
        assertValue(record, "result.condition.met", is(true));
        assertValue(record, "result.actions.0.id", is("log"));
        assertValue(record, "result.actions.0.type", is("logging"));
        assertValue(record, "result.actions.0.status", is("success"));
        assertValue(record, "result.actions.0.logging.logged_text", is("_text"));
    }

    @Test @Repeat(iterations = 20)
    public void testExecute_ActionMode() throws Exception {
        final WatcherClient watcherClient = watcherClient();

        PutWatchResponse putWatchResponse = watcherClient.preparePutWatch()
                .setId("_id")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("1s"))) // run every second so we can ack it
                        .input(simpleInput("foo", "bar"))
                        .defaultThrottlePeriod(TimeValue.timeValueMillis(0))
                        .condition(alwaysCondition())
                        .addAction("log", loggingAction("_text")))
                .get();

        assertThat(putWatchResponse.isCreated(), is(true));

        boolean execute = randomBoolean();
        boolean force = randomBoolean();
        ActionExecutionMode mode;
        if (randomBoolean()) {
            mode = ActionExecutionMode.SKIP;
        } else {
            if (execute && force) {
                mode = ActionExecutionMode.FORCE_EXECUTE;
            } else if (execute) {
                mode = ActionExecutionMode.EXECUTE;
            } else if (force) {
                mode = ActionExecutionMode.FORCE_SIMULATE;
            } else {
                mode = ActionExecutionMode.SIMULATE;
            }
        }

        if (mode.force()) {
            // since we're forcing, lets ack the action, such that it'd suppoed to be throttled
            // but forcing will ignore the throttling

            // lets wait for the watch to be ackable
            if (timeWarped()) {
                timeWarp().scheduler().trigger("_id");
            } else {
                assertBusy(new Runnable() {
                    @Override
                    public void run() {
                        GetWatchResponse getWatchResponse = watcherClient.prepareGetWatch("_id").get();
                        assertValue(getWatchResponse.getSource(), "status.actions.log.ack.state", is("ackable"));
                    }
                });
            }

            String[] actionIds = randomFrom(
                    new String[] { "_all" },
                    new String[] { "log" },
                    new String[] { "foo", "_all" },
                    null
            );
            AckWatchRequestBuilder ackWatchRequestBuilder = watcherClient.prepareAckWatch("_id");
            if (actionIds != null) {
                ackWatchRequestBuilder.setActionIds(actionIds);
            }
            AckWatchResponse ackWatchResponse = ackWatchRequestBuilder.get();
            assertThat(ackWatchResponse, notNullValue());
            WatchStatus status = ackWatchResponse.getStatus();
            assertThat(status, notNullValue());
            ActionStatus actionStatus = status.actionStatus("log");
            assertThat(actionStatus, notNullValue());
            assertThat(actionStatus.ackStatus().state(), is(ActionStatus.AckStatus.State.ACKED));
        }

        ExecuteWatchResponse response = watcherClient.prepareExecuteWatch("_id")
                .setActionMode(randomBoolean() ? "log" : "_all", mode)
                .get();
        assertThat(response, notNullValue());
        assertThat(response.getRecordId(), notNullValue());
        Wid wid = new Wid(response.getRecordId());
        assertThat(wid.watchId(), is("_id"));

        XContentSource record = response.getRecordSource();
        assertValue(record, "watch_id", is("_id"));
        assertValue(record, "trigger_event.type", is("manual"));
        assertValue(record, "trigger_event.triggered_time", notNullValue());
        String triggeredTime = record.getValue("trigger_event.triggered_time");
        assertValue(record, "trigger_event.manual.schedule.scheduled_time", is(triggeredTime));
        if (mode == ActionExecutionMode.SKIP) {
            assertValue(record, "state", is("throttled"));
        } else {
            assertValue(record, "state", is("executed"));
        }
        assertValue(record, "input.simple.foo", is("bar"));
        assertValue(record, "condition.always", notNullValue());
        assertValue(record, "result.execution_time", notNullValue());
        assertValue(record, "result.execution_duration", notNullValue());
        assertValue(record, "result.input.type", is("simple"));
        assertValue(record, "result.input.payload.foo", is("bar"));
        assertValue(record, "result.condition.type", is("always"));
        assertValue(record, "result.condition.met", is(true));
        assertValue(record, "result.actions.0.id", is("log"));
        assertValue(record, "result.actions.0.type", is("logging"));
        switch (mode) {
            case SKIP: // the action should be manually skipped/throttled
                assertValue(record, "result.actions.0.status", is("throttled"));
                assertValue(record, "result.actions.0.reason", is("manually skipped"));
                break;
            default:
                if (mode.simulate()) {
                    assertValue(record, "result.actions.0.status", is("simulated"));
                } else {
                    assertValue(record, "result.actions.0.status", is("success"));
                }
                assertValue(record, "result.actions.0.logging.logged_text", is("_text"));
        }
    }
}

package com.hust.ali.jstorm.example.sequence.spout;

import backtype.storm.Config;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;
import com.alibaba.jstorm.client.ConfigExtension;
import com.alibaba.jstorm.task.execute.spout.SpoutCollector;
import com.alibaba.jstorm.utils.JStormUtils;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import com.hust.ali.jstorm.example.TpsCounter;
import com.hust.ali.jstorm.example.sequence.SequenceTopologyDef;
import com.hust.ali.jstorm.example.sequence.bean.Pair;
import com.hust.ali.jstorm.example.sequence.bean.PairMaker;
import com.hust.ali.jstorm.example.sequence.bean.TradeCustomer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by zx on 2016/4/20.
 */

public class SequenceSpout implements IRichSpout {
    private static final long serialVersionUID = 1L;

    public static final Logger LOG = LoggerFactory.getLogger(SequenceSpout.class);

    SpoutOutputCollector collector;

    // I special use long not AtomicLong to check competition
    private long tupleId;
    private long succeedCount;
    private long failedCount;

    private AtomicLong handleCounter = new AtomicLong(0);

    private Long MAX_PENDING_COUNTER;

    private TpsCounter tpsCounter;

    private boolean isFinished;

    private boolean isLimited = false;

    private long SPOUT_MAX_SEND_NUM;

    private int bufferLen = 0;
    private Random random;

    private boolean isSendCtrlMsg = false;

    public boolean isDistributed() {
        return true;
    }

    public long getMaxPending(Map conf) {
        // if single spout thread, MAX_PENDING should be Long.MAX_VALUE
        if (ConfigExtension.isSpoutSingleThread(conf)) {
            return Long.MAX_VALUE;
        }

        Object pending = conf.get(Config.TOPOLOGY_MAX_SPOUT_PENDING);
        if (pending == null) {
            return Long.MAX_VALUE;
        }

        int pendingNum = JStormUtils.parseInt(pending);
        if (pendingNum == 1) {
            return Long.MAX_VALUE;
        }

        return pendingNum;
    }

    public void open(Map conf, TopologyContext context,
                     SpoutOutputCollector collector) {
        this.collector = collector;

        if (conf.get("spout.max.sending.num") == null) {
            isLimited = false;
        } else {
            isLimited = true;
            SPOUT_MAX_SEND_NUM = JStormUtils.parseLong(conf
                    .get("spout.max.sending.num"));
        }

        Boolean btrue = JStormUtils.parseBoolean(conf.get("spout.send.contrl.message"));
        if (btrue != null && btrue) {
            isSendCtrlMsg = true;
        } else {
            isSendCtrlMsg = false;
        }

        isFinished = false;

        tpsCounter = new TpsCounter(context.getThisComponentId() + ":"
                + context.getThisTaskId());

        MAX_PENDING_COUNTER = getMaxPending(conf);

        bufferLen = JStormUtils.parseInt(conf.get("byte.buffer.len"), 0);

        random = new Random();
        random.setSeed(System.currentTimeMillis());

        JStormUtils.sleepMs(20 * 1000);

        LOG.info("Finish open, buffer Len:" + bufferLen);
    }

    private AtomicLong tradeSum = new AtomicLong(0);
    private AtomicLong customerSum = new AtomicLong(0);

    public void emit() {
        String buffer = null;
        if (bufferLen > 0) {
            byte[] byteBuffer = new byte[bufferLen];

            for (int i = 0; i < bufferLen; i++) {
                byteBuffer[i] = (byte) random.nextInt(200);
            }
            buffer = new String(byteBuffer);
        }


        Pair trade = PairMaker.makeTradeInstance();
        Pair customer = PairMaker.makeCustomerInstance();

        TradeCustomer tradeCustomer = new TradeCustomer();
        tradeCustomer.setTrade(trade);
        tradeCustomer.setCustomer(customer);
        tradeCustomer.setBuffer(buffer);

        tradeSum.addAndGet(trade.getValue());
        customerSum.addAndGet(customer.getValue());

        collector.emit(new Values(tupleId, tradeCustomer), tupleId);
        tupleId++;
        handleCounter.incrementAndGet();
        while (handleCounter.get() >= MAX_PENDING_COUNTER - 1) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ignored) {
            }
        }

        tpsCounter.count();

    }

    public void nextTuple() {
        if (!isLimited) {
            emit();
            return;
        }

        if (isFinished) {
            if (isSendCtrlMsg) {
                //	LOG.info("spout will send control message due to finish sending ");
                long now = System.currentTimeMillis();
                String ctrlMsg = "spout don't send message due to pending num at " + now;
                ((SpoutCollector) (collector.getDelegate())).emitCtrl(SequenceTopologyDef.CONTROL_STREAM_ID, new Values(String.valueOf(now)), ctrlMsg);
            }
            LOG.info("Finish sending ");
            JStormUtils.sleepMs(500);
            return;
        }

        if (tupleId > SPOUT_MAX_SEND_NUM) {
            isFinished = true;
            return;
        }

        emit();
    }

    public void close() {
        tpsCounter.cleanup();
        LOG.info("Sending :" + tupleId + ", success:" + succeedCount
                + ", failed:" + failedCount);
        LOG.info("tradeSum:" + tradeSum + ",cumsterSum" + customerSum);
    }

    public void ack(Object id) {
        succeedCount++;
        handleCounter.decrementAndGet();
    }

    public void fail(Object id) {
        failedCount++;
        handleCounter.decrementAndGet();
        if (id instanceof Long) {
            Long failId = (Long) id;
            LOG.info("Failed to handle " + failId);
        } else if (id instanceof String) {
            LOG.info("Failed to handle " + id);
        }
    }

    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("ID", "RECORD"));
        declarer.declareStream(SequenceTopologyDef.CONTROL_STREAM_ID, new Fields("CONTROL"));
        // declarer.declare(new Fields("ID"));
    }

    public Map<String, Object> getComponentConfiguration() {
        return null;
    }

    public void activate() {
        LOG.info("Start active");
    }

    public void deactivate() {
        LOG.info("Start deactive");
    }

}

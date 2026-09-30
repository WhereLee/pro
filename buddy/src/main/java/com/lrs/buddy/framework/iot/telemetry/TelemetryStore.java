package com.lrs.buddy.framework.iot.telemetry;

import com.lrs.buddy.framework.iot.repo.IngestDao;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 遥测存储端口。
 *
 * 为什么必须留端口（swap-plan.md §7 A3）：本地用 MySQL 分区表实证，
 * 生产量级上来要能换 IoTDB 而不动业务代码 —— TDengine 服务端无 Windows 原生包，
 * 所以它连"本地可证"这一条都不满足，不作为默认实现。
 *
 * 写入失败**不得影响指令与订单链路**：本接口的实现必须自己处理异常，
 * 只上报计数与告警，绝不上抛到接收线程。
 */
public interface TelemetryStore {

    void saveBatch(List<TelemetryRecord> records);

    record TelemetryRecord(Long deviceRowId, String productKey, String metricKind, String propsJson,
                           long tsMillis, Long seq, long tenantId) {
    }

    @RequiredArgsConstructor
    class MysqlStore implements TelemetryStore {

        private final IngestDao ingestDao;

        @Override
        public void saveBatch(List<TelemetryRecord> records) {
            if (records.isEmpty()) {
                return;
            }
            List<IngestDao.TelemetryRow> rows = records.stream()
                    .map(r -> new IngestDao.TelemetryRow(nextId(), r.deviceRowId(), r.productKey(), r.metricKind(),
                            r.propsJson(), r.tsMillis(), r.seq(), r.tenantId()))
                    .toList();
            ingestDao.insertTelemetryBatch(rows);
        }

        private static long nextId() {
            return java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        }
    }
}

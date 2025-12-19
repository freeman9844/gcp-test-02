package com.example.dataflow.utils;

import org.apache.beam.sdk.io.GenerateSequence;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;

import java.util.Random;

/**
 * 가상 데이터를 생성하는 PTransform (Hot Key 시뮬레이션용)
 * 
 * 설계 목적:
 * - 파이프라인의 Hot Key 처리 로직을 검증하기 위해 인위적으로 키 분포를 왜곡시킵니다.
 * - [Best Practice] Unbounded 리소스를 시뮬레이션하기 위해 GenerateSequence를 사용합니다.
 */
public class SyntheticDataGenerator extends PTransform<PBegin, PCollection<KV<String, String>>> {

    @Override
    public PCollection<KV<String, String>> expand(PBegin input) {
        // 초당 10000개의 이벤트를 무한히 생성합니다.
        return input.apply("GenerateSequence", GenerateSequence.from(0).withRate(10000, Duration.standardSeconds(1)))
                .apply("MapToSkewedKeys", org.apache.beam.sdk.transforms.ParDo.of(new DoFn<Long, KV<String, String>>() {
                    private transient Random random;

                    /**
                     * [Best Practice] DoFn.Setup 메서드 사용
                     * - 매번 인스턴스를 생성하지 않고, 워커에서 한 번만 초기화하여 성능 부하를 줄입니다.
                     */
                    @Setup
                    public void setup() {
                        this.random = new Random();
                    }

                    @ProcessElement
                    public void processElement(ProcessContext c) {
                        String key;
                        int roll = random.nextInt(100);

                        // 인위적인 데이터 스큐(Skew) 부여 (80%의 데이터가 특정 2개 키에 집중)
                        if (roll < 80) {
                            if (roll < 50) {
                                // 전체 데이터의 50%를 차지하는 매우 뜨거운 키
                                key = "hot-key-A";
                            } else {
                                // 약 30%를 차지하는 비교적 뜨거운 키
                                key = "hot-key-B";
                            }
                        } else {
                            // 20%는 1000개의 다양한 키에 골고루 분산하여 Long-Tail 시뮬레이션
                            key = "key-" + random.nextInt(1000);
                        }

                        // 현재는 키 중심의 분석이므로 값은 더미 문자열을 전송합니다.
                        c.output(KV.of(key, "payload"));
                    }
                }));
    }
}

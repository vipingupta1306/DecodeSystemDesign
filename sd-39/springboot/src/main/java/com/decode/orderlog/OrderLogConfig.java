package com.decode.orderlog;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class OrderLogConfig {

    /** SOLUTION: 3 copies, at least 2 must have the write, a copy that fell behind can never become leader. */
    @Bean
    NewTopic orderAssignments(@Value("${orderlog.topic}") String name,
                              @Value("${orderlog.partitions}") int partitions,
                              @Value("${orderlog.replication-factor}") short rf,
                              @Value("${orderlog.min-insync-replicas}") String minIsr) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(rf)
                .config("min.insync.replicas", minIsr)
                .config("unclean.leader.election.enable", "false")
                .build();
    }
}

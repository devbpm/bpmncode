-- Tablas del journal de bpmncode (JdbcProcessInstanceStore). Sintaxis de H2 y PostgreSQL.
-- Idempotente: se puede ejecutar en cada arranque.

create table if not exists pac_process_instance (
    id                            uuid                        not null primary key,
    process_id                    varchar(80)                 not null,
    process_version               integer                     not null,
    correlation_key               uuid                        not null,
    business_key                  varchar(80),
    state                         varchar(20)                 not null,
    current_flow_node_id          varchar(80)                 not null,
    loop_counter                  integer                     not null,
    selected_sequence_flow_id     varchar(160),
    triggering_message_id         uuid,
    triggering_message            varchar(120),
    triggering_source             varchar(120),
    triggering_source_instance_id uuid,
    triggering_occurred_at        timestamp(6) with time zone,
    published_message_id          uuid,
    published_message             varchar(120),
    started_at                    timestamp(6) with time zone not null,
    ended_at                      timestamp(6) with time zone,
    version                       bigint                      not null,
    constraint uk_pac_process_instance_correlation unique (process_id, correlation_key)
);

create table if not exists pac_flow_node_instance (
    id                  uuid                        not null primary key,
    process_instance_id uuid                        not null,
    sequence            integer                     not null,
    process_id          varchar(80)                 not null,
    process_version     integer                     not null,
    correlation_key     uuid                        not null,
    business_key        varchar(80),
    flow_node_id        varchar(80)                 not null,
    element_type        varchar(30)                 not null,
    loop_counter        integer                     not null,
    occurred_at         timestamp(6) with time zone not null,
    outcome             varchar(500)                not null
);

create index if not exists ix_pac_flow_node_instance_process_instance on pac_flow_node_instance (process_instance_id);
create index if not exists ix_pac_flow_node_instance_process_version on pac_flow_node_instance (process_id, process_version);
create index if not exists ix_pac_process_instance_process_version on pac_process_instance (process_id, process_version);

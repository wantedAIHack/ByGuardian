create table cases (
    id                 uuid primary key,
    observation_set    text not null,
    start_date         date not null,
    diagnosis          text not null,
    paretic_side       text not null,
    verbal_difficulty  text not null,
    next_visit_date    date,
    recovery_code_hash text not null unique,
    extra_questions    json not null,
    created_at         timestamp with time zone not null
);

create table guardians (
    id         uuid primary key,
    case_id    uuid not null references cases (id),
    relation   text not null,
    token_hash text not null unique,
    created_at timestamp with time zone not null
);

create table therapist_links (
    id         uuid primary key,
    case_id    uuid not null references cases (id),
    token_hash text not null unique,
    revoked    boolean not null default false,
    created_at timestamp with time zone not null
);

create table snapshots (
    id          uuid primary key,
    case_id     uuid not null references cases (id),
    week        int not null,
    kind        text not null,
    no_change   boolean not null,
    author_id   uuid not null references guardians (id),
    body        json not null,
    recorded_at timestamp with time zone not null,
    unique (case_id, week)
);

create table question_cache (
    id           uuid primary key,
    case_id      uuid not null unique references cases (id),
    week         int not null,
    status       text not null,
    body         json not null,
    generated_at timestamp with time zone not null
);

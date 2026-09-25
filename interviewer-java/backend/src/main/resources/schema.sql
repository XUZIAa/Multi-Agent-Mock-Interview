-- 表结构与 Python 版逐列一致，老库可以直接被这个后端接管。
--
-- 时间戳一律 TEXT，格式 yyyy-MM-dd HH:mm:ss.SSSSSS，值是 UTC 且不带时区后缀——
-- SQLAlchemy 的 SQLite DATETIME 就是这么存的，换格式会读不出用户的历史记录。
--
-- 大对象走 JSON 文本列（payload / state），关键查询字段单独提列建索引：
-- 成长曲线要按维度聚合、错题本要按主题筛，解析大 JSON 做不了这些事。

CREATE TABLE IF NOT EXISTS personas (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    archetype   TEXT    NOT NULL,
    is_builtin  INTEGER NOT NULL DEFAULT 0,
    usage_count INTEGER NOT NULL DEFAULT 0,
    payload     TEXT    NOT NULL,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL,
    CONSTRAINT uq_persona_name UNIQUE (name)
);
CREATE INDEX IF NOT EXISTS ix_personas_name ON personas (name);
CREATE INDEX IF NOT EXISTS ix_personas_is_builtin ON personas (is_builtin);
CREATE INDEX IF NOT EXISTS ix_personas_created_at ON personas (created_at);

CREATE TABLE IF NOT EXISTS resumes (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    label      TEXT NOT NULL,
    file_path  TEXT NOT NULL DEFAULT '',
    payload    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_resumes_label ON resumes (label);
CREATE INDEX IF NOT EXISTS ix_resumes_created_at ON resumes (created_at);

CREATE TABLE IF NOT EXISTS jobs (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    label      TEXT NOT NULL,
    company    TEXT NOT NULL DEFAULT '',
    title      TEXT NOT NULL DEFAULT '',
    payload    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_jobs_label ON jobs (label);
CREATE INDEX IF NOT EXISTS ix_jobs_created_at ON jobs (created_at);

CREATE TABLE IF NOT EXISTS gap_reports (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    resume_id   INTEGER NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    job_id      INTEGER NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    match_score INTEGER NOT NULL DEFAULT 0,
    payload     TEXT    NOT NULL,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_gap_reports_resume_id ON gap_reports (resume_id);
CREATE INDEX IF NOT EXISTS ix_gap_reports_job_id ON gap_reports (job_id);
CREATE INDEX IF NOT EXISTS ix_gap_pair ON gap_reports (resume_id, job_id);
CREATE INDEX IF NOT EXISTS ix_gap_reports_created_at ON gap_reports (created_at);

CREATE TABLE IF NOT EXISTS sessions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    title           TEXT    NOT NULL DEFAULT '',
    status          TEXT    NOT NULL DEFAULT 'draft',
    persona_id      INTEGER REFERENCES personas (id) ON DELETE SET NULL,
    persona_name    TEXT    NOT NULL DEFAULT '',
    resume_id       INTEGER REFERENCES resumes (id) ON DELETE SET NULL,
    job_id          INTEGER REFERENCES jobs (id) ON DELETE SET NULL,
    planned_minutes INTEGER NOT NULL DEFAULT 35,
    duration_ms     INTEGER NOT NULL DEFAULT 0,
    overall_score   REAL,
    started_at      TEXT,
    ended_at        TEXT,
    audio_path      TEXT    NOT NULL DEFAULT '',
    state           TEXT    NOT NULL DEFAULT '{}',
    created_at      TEXT    NOT NULL,
    updated_at      TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_sessions_status ON sessions (status);
CREATE INDEX IF NOT EXISTS ix_sessions_overall_score ON sessions (overall_score);
CREATE INDEX IF NOT EXISTS ix_sessions_created_at ON sessions (created_at);

CREATE TABLE IF NOT EXISTS turns (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      INTEGER NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_index      INTEGER NOT NULL,
    speaker         TEXT    NOT NULL,
    text            TEXT    NOT NULL DEFAULT '',
    started_at_ms   INTEGER NOT NULL DEFAULT 0,
    duration_ms     INTEGER NOT NULL DEFAULT 0,
    intent          TEXT    NOT NULL DEFAULT '',
    was_interrupted INTEGER NOT NULL DEFAULT 0,
    question_index  INTEGER,
    CONSTRAINT uq_turn_seq UNIQUE (session_id, turn_index)
);
CREATE INDEX IF NOT EXISTS ix_turns_session_id ON turns (session_id);
CREATE INDEX IF NOT EXISTS ix_turns_speaker ON turns (speaker);

CREATE TABLE IF NOT EXISTS questions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      INTEGER NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    question_index  INTEGER NOT NULL,
    phase           TEXT    NOT NULL,
    intent          TEXT    NOT NULL DEFAULT '',
    brief           TEXT    NOT NULL DEFAULT '',
    target_skill    TEXT    NOT NULL DEFAULT '',
    spoken_text     TEXT    NOT NULL DEFAULT '',
    answer_text     TEXT    NOT NULL DEFAULT '',
    asked_at_ms     INTEGER NOT NULL DEFAULT 0,
    follow_up_depth INTEGER NOT NULL DEFAULT 0,
    quality         REAL,
    CONSTRAINT uq_question_seq UNIQUE (session_id, question_index)
);
CREATE INDEX IF NOT EXISTS ix_questions_session_id ON questions (session_id);
CREATE INDEX IF NOT EXISTS ix_questions_phase ON questions (phase);
CREATE INDEX IF NOT EXISTS ix_questions_target_skill ON questions (target_skill);

CREATE TABLE IF NOT EXISTS reviews (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    INTEGER NOT NULL UNIQUE REFERENCES sessions (id) ON DELETE CASCADE,
    overall_score REAL    NOT NULL DEFAULT 0.0,
    payload       TEXT    NOT NULL,
    created_at    TEXT    NOT NULL,
    updated_at    TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_reviews_created_at ON reviews (created_at);

CREATE TABLE IF NOT EXISTS mistakes (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id       INTEGER REFERENCES sessions (id) ON DELETE CASCADE,
    knowledge_point  TEXT    NOT NULL,
    topic            TEXT    NOT NULL DEFAULT '',
    question         TEXT    NOT NULL DEFAULT '',
    candidate_answer TEXT    NOT NULL DEFAULT '',
    key_points       TEXT    NOT NULL DEFAULT '[]',
    severity         TEXT    NOT NULL DEFAULT 'major',
    review_hint      TEXT    NOT NULL DEFAULT '',
    hit_count        INTEGER NOT NULL DEFAULT 1,
    mastered         INTEGER NOT NULL DEFAULT 0,
    last_seen_at     TEXT    NOT NULL,
    created_at       TEXT    NOT NULL,
    updated_at       TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_mistakes_session_id ON mistakes (session_id);
CREATE INDEX IF NOT EXISTS ix_mistakes_knowledge_point ON mistakes (knowledge_point);
CREATE INDEX IF NOT EXISTS ix_mistakes_topic ON mistakes (topic);
CREATE INDEX IF NOT EXISTS ix_mistakes_severity ON mistakes (severity);
CREATE INDEX IF NOT EXISTS ix_mistakes_mastered ON mistakes (mastered);
CREATE INDEX IF NOT EXISTS ix_mistakes_created_at ON mistakes (created_at);

CREATE TABLE IF NOT EXISTS skill_trends (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  INTEGER NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    dimension   TEXT    NOT NULL,
    score       REAL    NOT NULL DEFAULT 0.0,
    recorded_at TEXT    NOT NULL,
    CONSTRAINT uq_trend_point UNIQUE (session_id, dimension)
);
CREATE INDEX IF NOT EXISTS ix_skill_trends_session_id ON skill_trends (session_id);
CREATE INDEX IF NOT EXISTS ix_skill_trends_dimension ON skill_trends (dimension);
CREATE INDEX IF NOT EXISTS ix_skill_trends_recorded_at ON skill_trends (recorded_at);

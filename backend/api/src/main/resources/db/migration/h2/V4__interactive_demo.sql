alter table cases add column demo_mode boolean not null default false;
alter table cases add column demo_today date;
alter table cases add constraint cases_demo_time_consistent check (
    (demo_mode and demo_today is not null) or (not demo_mode and demo_today is null)
);

-- A person's picture: a preset code or a small photo as a data URL (checked by the service).
alter table users add column avatar text;
alter table users add constraint users_avatar_size check (avatar is null or length(avatar) <= 90000);
comment on column users.avatar is 'Аватар: preset:<код> или data:image/...;base64 до 64 КБ; пусто — инициалы';

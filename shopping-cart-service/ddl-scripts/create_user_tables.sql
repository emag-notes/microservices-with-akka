CREATE TABLE IF NOT EXISTS public.item_popularity (
    item_id VARCHAR(255) NOT NULL,
    count BIGINT NOT NULL,
    PRIMARY KEY (item_id)
);

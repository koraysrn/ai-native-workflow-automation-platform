-- Persist task decision comments (issue #29).

ALTER TABLE tasks ADD COLUMN decision_comment VARCHAR(2000);

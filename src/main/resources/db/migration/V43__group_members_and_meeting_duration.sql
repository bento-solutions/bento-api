-- group_member has existed since V2 but the application never read or wrote it (see the note in
-- V20), so every existing group is about to appear empty. Seed it with the people who have already
-- taken part: whoever created the group, wrote in it, or was invited to one of its meetings.
-- Anyone else can be added from the group's edit form.
INSERT INTO group_member (group_id, user_id)
SELECT g.id, u.id
FROM crm_group g
JOIN app_user u ON u.id = g.created_by AND u.organization_id = g.organization_id
UNION
SELECT m.group_id, u.id
FROM group_message m
JOIN app_user u ON u.id = m.author_user_id AND u.organization_id = m.organization_id
UNION
SELECT mt.group_id, u.id
FROM group_meeting mt
CROSS JOIN LATERAL unnest(mt.attendee_user_ids) AS attendee(user_id)
JOIN app_user u ON u.id = attendee.user_id AND u.organization_id = mt.organization_id
ON CONFLICT DO NOTHING;

-- The scheduling form has always asked for a duration; nothing stored it.
ALTER TABLE group_meeting ADD COLUMN duration_minutes INTEGER;

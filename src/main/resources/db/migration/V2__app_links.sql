-- Links shown on the public download page (editable in Admin > Settings > Mobile apps)
INSERT INTO settings(key, value, secret) VALUES
 ('app.android_url', '', false),
 ('app.ios_url', '', false),
 ('app.latest_version', '1.0.0', false)
ON CONFLICT (key) DO NOTHING;

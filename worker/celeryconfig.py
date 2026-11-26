import os
broker_url = os.getenv("CELERY_BROKER_URL", "redis://localhost:6379/0")
task_serializer = "json"
accept_content = ["json"]
timezone = "Africa/Harare"
task_ignore_result = True
worker_prefetch_multiplier = 1
beat_schedule = {
    "expire-stale-offers": {"task": "haulmate.maintenance.expire_offers", "schedule": 600.0},
}

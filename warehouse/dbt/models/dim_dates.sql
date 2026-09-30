select distinct event_date,
    year(event_date) as year_number,
    month(event_date) as month_number,
    day(event_date) as day_number,
    isodow(event_date) as iso_weekday,
    week(event_date) as iso_week
from {{ ref('stg_events') }}

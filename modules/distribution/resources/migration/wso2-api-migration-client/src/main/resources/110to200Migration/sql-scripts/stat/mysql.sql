ALTER TABLE API_LAST_ACCESS_TIME_SUMMARY MODIFY COLUMN tenantDomain VARCHAR(255);
ALTER TABLE API_LAST_ACCESS_TIME_SUMMARY MODIFY COLUMN apiPublisher VARCHAR(255);
ALTER TABLE API_LAST_ACCESS_TIME_SUMMARY MODIFY COLUMN api VARCHAR(255);
ALTER TABLE API_LAST_ACCESS_TIME_SUMMARY MODIFY COLUMN version VARCHAR(255);
ALTER TABLE API_LAST_ACCESS_TIME_SUMMARY MODIFY COLUMN userid VARCHAR(255);
ALTER TABLE API_LAST_ACCESS_TIME_SUMMARY MODIFY COLUMN context VARCHAR(255);
ALTER TABLE API_LAST_ACCESS_TIME_SUMMARY ADD PRIMARY KEY(tenantDomain,apiPublisher,api);


ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN api VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN api_version VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN version VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN apiPublisher VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN consumerKey VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN userId VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN context VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN hostName VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY MODIFY COLUMN time VARCHAR(255);
ALTER TABLE API_REQUEST_SUMMARY ADD PRIMARY KEY(api,api_version,version,apiPublisher,consumerKey,userId,context,hostName,year,month,day);


ALTER TABLE API_VERSION_USAGE_SUMMARY MODIFY COLUMN api VARCHAR(255);
ALTER TABLE API_VERSION_USAGE_SUMMARY MODIFY COLUMN version VARCHAR(255);
ALTER TABLE API_VERSION_USAGE_SUMMARY MODIFY COLUMN apiPublisher VARCHAR(255);
ALTER TABLE API_VERSION_USAGE_SUMMARY MODIFY COLUMN context VARCHAR(255);
ALTER TABLE API_VERSION_USAGE_SUMMARY MODIFY COLUMN hostName VARCHAR(255);
ALTER TABLE API_VERSION_USAGE_SUMMARY MODIFY COLUMN time VARCHAR(255);
ALTER TABLE API_VERSION_USAGE_SUMMARY ADD PRIMARY KEY(api,version,apiPublisher,context,hostName,year,month,day);


ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN api VARCHAR(255);
ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN version VARCHAR(255);
ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN apiPublisher VARCHAR(255);
ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN consumerKey VARCHAR(255);
ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN resourcePath VARCHAR(255);
ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN context VARCHAR(255);
ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN method VARCHAR(255);
ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN hostName VARCHAR(255);
ALTER TABLE API_Resource_USAGE_SUMMARY MODIFY COLUMN time VARCHAR(255);
-- alter table API_Resource_USAGE_SUMMARY add primary key(api,version,apiPublisher,consumerKey,context,resourcePath,method,hostName,year,month,day);
CREATE TABLE API_Resource_USAGE_SUMMARY_TMP SELECT api,version,apiPublisher,consumerKey,resourcePath,context,method,sum(total_request_count) as total_request_count,hostName,year,month,day,time
from API_Resource_USAGE_SUMMARY GROUP BY api,version,apiPublisher,consumerKey,context,resourcePath,method,hostName,year,month,day;
DROP TABLE API_Resource_USAGE_SUMMARY;
ALTER TABLE API_Resource_USAGE_SUMMARY_TMP RENAME TO API_Resource_USAGE_SUMMARY;
ALTER TABLE API_Resource_USAGE_SUMMARY ADD PRIMARY KEY(api,version,apiPublisher,consumerKey,context,resourcePath,method,hostName,year,month,day);

ALTER TABLE API_RESPONSE_SUMMARY MODIFY COLUMN api_version VARCHAR(255);
ALTER TABLE API_RESPONSE_SUMMARY MODIFY COLUMN apiPublisher VARCHAR(255);
ALTER TABLE API_RESPONSE_SUMMARY MODIFY COLUMN context VARCHAR(255);
ALTER TABLE API_RESPONSE_SUMMARY MODIFY COLUMN hostName VARCHAR(255);
ALTER TABLE API_RESPONSE_SUMMARY MODIFY COLUMN time VARCHAR(255);
ALTER TABLE API_RESPONSE_SUMMARY ADD PRIMARY KEY(api_version,apiPublisher,context,hostName,year,month,day);


ALTER TABLE API_FAULT_SUMMARY MODIFY COLUMN api VARCHAR(255);
ALTER TABLE API_FAULT_SUMMARY MODIFY COLUMN version VARCHAR(255);
ALTER TABLE API_FAULT_SUMMARY MODIFY COLUMN apiPublisher VARCHAR(255);
ALTER TABLE API_FAULT_SUMMARY MODIFY COLUMN consumerKey VARCHAR(255);
ALTER TABLE API_FAULT_SUMMARY MODIFY COLUMN context VARCHAR(255);
ALTER TABLE API_FAULT_SUMMARY MODIFY COLUMN hostName VARCHAR(255);
ALTER TABLE API_FAULT_SUMMARY MODIFY COLUMN time VARCHAR(255);
ALTER TABLE API_FAULT_SUMMARY ADD PRIMARY KEY(api,version,apiPublisher,consumerKey,context,hostName,year,month,day);



ALTER TABLE API_DESTINATION_SUMMARY MODIFY COLUMN api VARCHAR(255);
ALTER TABLE API_DESTINATION_SUMMARY MODIFY COLUMN version VARCHAR(255);
ALTER TABLE API_DESTINATION_SUMMARY MODIFY COLUMN apiPublisher VARCHAR(255);
ALTER TABLE API_DESTINATION_SUMMARY MODIFY COLUMN context VARCHAR(255);
ALTER TABLE API_DESTINATION_SUMMARY MODIFY COLUMN destination VARCHAR(255);
ALTER TABLE API_DESTINATION_SUMMARY MODIFY COLUMN hostName VARCHAR(255);
ALTER TABLE API_DESTINATION_SUMMARY MODIFY COLUMN time VARCHAR(255);
ALTER TABLE API_DESTINATION_SUMMARY ADD PRIMARY KEY(api,version,apiPublisher,context,destination,hostName,year,month,day);


ALTER TABLE API_THROTTLED_OUT_SUMMARY MODIFY COLUMN api VARCHAR(255);
ALTER TABLE API_THROTTLED_OUT_SUMMARY MODIFY COLUMN api_version VARCHAR(255);
ALTER TABLE API_THROTTLED_OUT_SUMMARY MODIFY COLUMN context VARCHAR(255);
ALTER TABLE API_THROTTLED_OUT_SUMMARY MODIFY COLUMN apiPublisher VARCHAR(255);
ALTER TABLE API_THROTTLED_OUT_SUMMARY MODIFY COLUMN applicationName VARCHAR(255);
ALTER TABLE API_THROTTLED_OUT_SUMMARY MODIFY COLUMN tenantDomain VARCHAR(255);
ALTER TABLE API_THROTTLED_OUT_SUMMARY MODIFY COLUMN time VARCHAR(255);
ALTER TABLE API_THROTTLED_OUT_SUMMARY ADD COLUMN throttledOutReason VARCHAR(255) DEFAULT '-';

CREATE TABLE API_THROTTLED_OUT_SUMMARY_TMP SELECT api,api_version,context,apiPublisher,applicationName,tenantDomain,year,month,day,week,time,sum(success_request_count) as success_request_count,sum(throttleout_count) as throttleout_count,throttledOutReason
FROM API_THROTTLED_OUT_SUMMARY GROUP BY api,api_version,context,apiPublisher,applicationName,tenantDomain,year,month,day;
DROP TABLE API_THROTTLED_OUT_SUMMARY;
ALTER TABLE API_THROTTLED_OUT_SUMMARY_TMP RENAME TO API_THROTTLED_OUT_SUMMARY;
ALTER TABLE API_THROTTLED_OUT_SUMMARY ADD PRIMARY KEY(api,api_version,context,apiPublisher,applicationName,tenantDomain,year,month,day,throttledOutReason);

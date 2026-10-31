package com.mediassistrover.backend.service.impl;

import com.mediassistrover.backend.dto.report.response.OperationsOverviewResponse;
import com.mediassistrover.backend.dto.report.response.AnalyticsResponses.*;
import com.mediassistrover.backend.entity.Account;
import com.mediassistrover.backend.exception.*;
import com.mediassistrover.backend.enums.*;
import com.mediassistrover.backend.repository.*;
import com.mediassistrover.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {
    private final JobRepository jobRepository;
    private final RobotRepository robotRepository;
    private final ServiceRequestRepository requestRepository;
    private final AlertRepository alertRepository;
    private final EmergencyIncidentRepository incidentRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Value("${app.reports.export-max-rows:5000}") private int exportMaxRows;

    @Override
    public OperationsOverviewResponse getOperationsOverview() {
        Map<JobStatus, Long> jobs = zeroed(JobStatus.class);
        jobRepository.countGroupedByStatus().forEach(row -> jobs.put(row.getStatus(), row.getTotal()));
        Map<RobotStatus, Long> robots = zeroed(RobotStatus.class);
        robotRepository.countGroupedByStatus().forEach(row -> robots.put(row.getStatus(), row.getTotal()));
        Map<ServiceRequestType, Long> requests = zeroed(ServiceRequestType.class);
        requestRepository.countGroupedByType().forEach(row -> requests.put(row.getRequestType(), row.getTotal()));

        long succeeded = jobs.get(JobStatus.SUCCEEDED);
        long terminal = succeeded + jobs.get(JobStatus.FAILED) + jobs.get(JobStatus.CANCELLED);
        double rate = terminal == 0 ? 0.0 : Math.round((succeeded * 10000.0) / terminal) / 100.0;
        long openAlerts = alertRepository.countByStatusIn(List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED));
        long activeIncidents = incidentRepository.countByStatusIn(List.of(
                EmergencyIncidentStatus.OPEN, EmergencyIncidentStatus.ACKNOWLEDGED,
                EmergencyIncidentStatus.IN_PROGRESS));
        return new OperationsOverviewResponse(Instant.now(clock), jobs, robots, requests,
                openAlerts, activeIncidents, terminal, succeeded, rate);
    }

    @Override
    public AnalyticsReport getAnalytics(Instant from, Instant to, String timezone, ReportInterval interval) {
        ZoneId zone = validateRange(from, to, timezone, interval);
        Map<String, Object> jobRow = jdbc.queryForMap("""
                SELECT COUNT(*) total,
                       COALESCE(SUM(status='SUCCEEDED'),0) succeeded,
                       COALESCE(SUM(status='FAILED'),0) failed,
                       COALESCE(SUM(status='CANCELLED'),0) cancelled,
                       COALESCE(SUM(status='RECOVERY_REQUIRED'),0) recovery_required,
                       AVG(CASE WHEN started_at IS NOT NULL AND ended_at IS NOT NULL
                           THEN TIMESTAMPDIFF(MICROSECOND,started_at,ended_at)/1000000.0 END) avg_duration
                FROM `Job` WHERE queued_at>=? AND queued_at<?
                """, Timestamp.from(from), Timestamp.from(to));
        long total = number(jobRow, "total").longValue();
        long succeeded = number(jobRow, "succeeded").longValue();
        long failed = number(jobRow, "failed").longValue();
        long cancelled = number(jobRow, "cancelled").longValue();
        long recovery = number(jobRow, "recovery_required").longValue();
        double terminal = succeeded + failed + cancelled + recovery;
        double successRate = terminal == 0 ? 0 : round(succeeded * 100.0 / terminal);
        double[] percentiles = durationPercentiles(from, to);
        JobMetrics jobs = new JobMetrics(total, succeeded, failed, cancelled, recovery, successRate,
                nullableDouble(jobRow.get("avg_duration")), nullable(percentiles[0]), nullable(percentiles[1]));

        Map<ServiceRequestType, Long> requestTypes = groupedEnum("request_type", ServiceRequestType.class, from, to);
        Map<ServiceRequestStatus, Long> requestStatuses = groupedEnum("status", ServiceRequestStatus.class, from, to);
        long requestTotal = requestTypes.values().stream().mapToLong(Long::longValue).sum();
        RequestMetrics requests = new RequestMetrics(requestTotal, requestTypes, requestStatuses);

        long robotCount = Optional.ofNullable(jdbc.queryForObject("SELECT COUNT(*) FROM `Robot`", Long.class)).orElse(0L);
        long activeMicros = Optional.ofNullable(jdbc.queryForObject("""
                SELECT COALESCE(SUM(TIMESTAMPDIFF(MICROSECOND,GREATEST(started_at,?),LEAST(ended_at,?))),0)
                FROM `Job` WHERE started_at IS NOT NULL AND ended_at IS NOT NULL AND started_at<? AND ended_at>?
                """, Long.class, Timestamp.from(from), Timestamp.from(to), Timestamp.from(to), Timestamp.from(from))).orElse(0L);
        long offlineEvents = Optional.ofNullable(jdbc.queryForObject("SELECT COUNT(*) FROM `RobotStatusHistory` " +
                "WHERE new_status='OFFLINE' AND recorded_at>=? AND recorded_at<?", Long.class,
                Timestamp.from(from), Timestamp.from(to))).orElse(0L);
        Double battery = nullableDouble(jdbc.queryForObject("SELECT AVG(battery_percent) FROM `Robot`", BigDecimal.class));
        double capacitySeconds = robotCount * (double) Duration.between(from, to).toSeconds();
        RobotMetrics robots = new RobotMetrics(robotCount, total,
                capacitySeconds == 0 ? 0 : round((activeMicros / 1_000_000.0) * 100.0 / capacitySeconds),
                offlineEvents, battery);

        IncidentMetrics incidents = new IncidentMetrics(
                count("Alert", "created_at", from, to), count("EmergencyIncident", "occurred_at", from, to),
                count("DetectionEvent", "detected_at", from, to), count("CrowdDensityRecord", "captured_at", from, to));
        DeliveryMetrics deliveries = deliveryMetrics(from, to);
        EmergencyMetrics emergencies = emergencyMetrics(from, to);
        MonitoringMetrics monitoring = monitoringMetrics(from, to);
        return new AnalyticsReport(Instant.now(clock), from, to, zone.getId(), interval, jobs, requests, robots,
                incidents, deliveries, emergencies, monitoring, timeline(from, to, zone, interval),
                workflowBreakdown(from, to), robotBreakdown(from, to), activityByMap(from, to),
                activityByZone(from, to));
    }

    @Override
    public Page<JobDetail> getJobDetails(Instant from, Instant to, UUID workflowId, UUID robotId,
                                         JobStatus status, Pageable pageable) {
        validateRange(from, to, "UTC", ReportInterval.DAY);
        int size = Math.min(Math.max(pageable.getPageSize(), 1), 100);
        int offset = Math.toIntExact(Math.min((long) pageable.getPageNumber() * size, Integer.MAX_VALUE));
        Filter filter = filter(from, to, workflowId, robotId, status);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + filter.fromWhere(), Long.class, filter.args().toArray());
        List<JobDetail> rows = queryJobDetails(filter, size, offset);
        return new PageImpl<>(rows, PageRequest.of(pageable.getPageNumber(), size), total == null ? 0 : total);
    }

    @Override
    @Transactional
    public CsvExport exportJobs(Instant from, Instant to, UUID workflowId, UUID robotId, JobStatus status,
                                UUID actorId, RequestMetadata metadata) {
        validateRange(from, to, "UTC", ReportInterval.DAY);
        Filter filter = filter(from, to, workflowId, robotId, status);
        List<JobDetail> rows = queryJobDetails(filter, exportMaxRows + 1, 0);
        if (rows.size() > exportMaxRows) throw new BusinessRuleException("CSV export exceeds " + exportMaxRows + " rows; narrow the filters");
        StringBuilder csv = new StringBuilder("jobId,jobCode,status,workflowCode,workflowVersion,robotCode,queuedAt,startedAt,endedAt,durationSeconds,errorCode\r\n");
        rows.forEach(row -> csv.append(csv(row.jobId())).append(',').append(csv(row.jobCode())).append(',')
                .append(csv(row.status())).append(',').append(csv(row.workflowCode())).append(',')
                .append(csv(row.workflowVersion())).append(',').append(csv(row.robotCode())).append(',')
                .append(csv(row.queuedAt())).append(',').append(csv(row.startedAt())).append(',')
                .append(csv(row.endedAt())).append(',').append(csv(row.durationSeconds())).append(',')
                .append(csv(row.errorCode())).append("\r\n"));
        Account actor = accountRepository.findById(actorId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + actorId));
        auditService.record(actor, "REPORT_CSV_EXPORTED", "JobReport", null, null,
                Map.of("from", from.toString(), "to", to.toString(), "rowCount", rows.size()), metadata);
        return new CsvExport(csv.toString().getBytes(StandardCharsets.UTF_8),
                "jobs-" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(clock)) + ".csv", rows.size());
    }

    private ZoneId validateRange(Instant from, Instant to, String timezone, ReportInterval interval) {
        if (from == null || to == null || !from.isBefore(to)) throw new BusinessRuleException("from must be before to");
        try {
            ZoneId zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
            long days = Duration.between(from, to).toDays();
            if (days > 366 || (interval == ReportInterval.HOUR && Duration.between(from, to).toHours() > 744))
                throw new BusinessRuleException("Report range is too large for the selected interval");
            return zone;
        } catch (DateTimeException exception) {
            throw new BusinessRuleException("Invalid timezone: " + timezone);
        }
    }

    private double[] durationPercentiles(Instant from, Instant to) {
        return jdbc.queryForObject("""
                WITH durations AS (
                    SELECT TIMESTAMPDIFF(MICROSECOND,started_at,ended_at)/1000000.0 seconds
                    FROM `Job` WHERE queued_at>=? AND queued_at<? AND started_at IS NOT NULL AND ended_at IS NOT NULL
                ), ranked AS (SELECT seconds,CUME_DIST() OVER (ORDER BY seconds) percentile FROM durations)
                SELECT MIN(CASE WHEN percentile>=0.50 THEN seconds END) p50,
                       MIN(CASE WHEN percentile>=0.95 THEN seconds END) p95 FROM ranked
                """, (rs, row) -> new double[]{rs.getObject("p50") == null ? -1 : rs.getDouble("p50"),
                rs.getObject("p95") == null ? -1 : rs.getDouble("p95")}, Timestamp.from(from), Timestamp.from(to));
    }

    private <E extends Enum<E>> Map<E, Long> groupedEnum(String column, Class<E> type, Instant from, Instant to) {
        Map<E, Long> result = zeroed(type);
        jdbc.query("SELECT `" + column + "` value,COUNT(*) total FROM `ServiceRequest` " +
                        "WHERE requested_at>=? AND requested_at<? GROUP BY `" + column + "`",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.put(Enum.valueOf(type, rs.getString("value")), rs.getLong("total")),
                Timestamp.from(from), Timestamp.from(to));
        return result;
    }

    private List<TimeBucket> timeline(Instant from, Instant to, ZoneId zone, ReportInterval interval) {
        List<TimeBucket> result = new ArrayList<>();
        ZonedDateTime cursor = from.atZone(zone);
        while (cursor.toInstant().isBefore(to)) {
            ZonedDateTime next = interval == ReportInterval.HOUR ? cursor.plusHours(1) : cursor.plusDays(1);
            Instant start = cursor.toInstant();
            Instant end = next.toInstant().isAfter(to) ? to : next.toInstant();
            Map<String, Object> row = jdbc.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(status='SUCCEEDED'),0) succeeded," +
                            "COALESCE(SUM(status='FAILED'),0) failed,COALESCE(SUM(status='RECOVERY_REQUIRED'),0) recovery " +
                            "FROM `Job` WHERE queued_at>=? AND queued_at<?", Timestamp.from(start), Timestamp.from(end));
            long alerts = count("Alert", "created_at", start, end);
            long emergencies = count("EmergencyIncident", "occurred_at", start, end);
            long detections = count("DetectionEvent", "detected_at", start, end);
            long crowds = count("CrowdDensityRecord", "captured_at", start, end);
            String label = interval == ReportInterval.HOUR
                    ? cursor.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX"))
                    : cursor.format(DateTimeFormatter.ISO_LOCAL_DATE);
            result.add(new TimeBucket(start, end, label, number(row,"total").longValue(),
                    number(row,"succeeded").longValue(), number(row,"failed").longValue(),
                    number(row,"recovery").longValue(), alerts, emergencies, detections, crowds));
            cursor = next;
        }
        return result;
    }

    private List<Breakdown> workflowBreakdown(Instant from, Instant to) {
        return jdbc.query("""
                SELECT w.workflow_code code,CONCAT(w.workflow_name,' v',w.version_no) label,COUNT(*) total,
                       COALESCE(SUM(j.status='SUCCEEDED'),0) succeeded,COALESCE(SUM(j.status='FAILED'),0) failed
                FROM `Job` j JOIN `Workflow` w ON w.workflow_id=j.workflow_id
                WHERE j.queued_at>=? AND j.queued_at<? GROUP BY w.workflow_id,w.workflow_code,w.workflow_name,w.version_no
                ORDER BY total DESC LIMIT 100
                """, (rs,row) -> breakdown(rs.getString("code"),rs.getString("label"),rs.getLong("total"),
                rs.getLong("succeeded"),rs.getLong("failed")), Timestamp.from(from), Timestamp.from(to));
    }

    private List<Breakdown> robotBreakdown(Instant from, Instant to) {
        return jdbc.query("""
                SELECT COALESCE(r.robot_code,'UNASSIGNED') code,COALESCE(r.robot_name,'Unassigned') label,COUNT(*) total,
                       COALESCE(SUM(j.status='SUCCEEDED'),0) succeeded,COALESCE(SUM(j.status='FAILED'),0) failed
                FROM `Job` j LEFT JOIN `Robot` r ON r.robot_id=j.robot_id
                WHERE j.queued_at>=? AND j.queued_at<? GROUP BY r.robot_id,r.robot_code,r.robot_name
                ORDER BY total DESC LIMIT 100
                """, (rs,row) -> breakdown(rs.getString("code"),rs.getString("label"),rs.getLong("total"),
                rs.getLong("succeeded"),rs.getLong("failed")), Timestamp.from(from), Timestamp.from(to));
    }

    private DeliveryMetrics deliveryMetrics(Instant from, Instant to) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) total,
                       COALESCE(SUM(d.delivered_at IS NOT NULL),0) delivered,
                       COALESCE(SUM(d.delivery_deadline IS NOT NULL AND
                           COALESCE(d.delivered_at,UTC_TIMESTAMP(6))>d.delivery_deadline),0) overdue,
                       AVG(CASE WHEN d.delivered_at IS NOT NULL THEN
                           TIMESTAMPDIFF(MICROSECOND,s.requested_at,d.delivered_at)/1000000.0 END) avg_fulfillment
                FROM `DeliveryRequest` d JOIN `ServiceRequest` s ON s.service_request_id=d.service_request_id
                WHERE s.requested_at>=? AND s.requested_at<?
                """, Timestamp.from(from), Timestamp.from(to));
        return new DeliveryMetrics(number(row,"total").longValue(), number(row,"delivered").longValue(),
                number(row,"overdue").longValue(), nullableDouble(value(row,"avg_fulfillment")));
    }

    private EmergencyMetrics emergencyMetrics(Instant from, Instant to) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) total,
                       COALESCE(SUM(acknowledged_at IS NOT NULL),0) acknowledged,
                       COALESCE(SUM(resolved_at IS NOT NULL),0) resolved,
                       AVG(CASE WHEN acknowledged_at IS NOT NULL THEN
                           TIMESTAMPDIFF(MICROSECOND,occurred_at,acknowledged_at)/1000000.0 END) avg_ack,
                       AVG(CASE WHEN resolved_at IS NOT NULL THEN
                           TIMESTAMPDIFF(MICROSECOND,occurred_at,resolved_at)/1000000.0 END) avg_resolve
                FROM `EmergencyIncident` WHERE occurred_at>=? AND occurred_at<?
                """, Timestamp.from(from), Timestamp.from(to));
        return new EmergencyMetrics(number(row,"total").longValue(), number(row,"acknowledged").longValue(),
                number(row,"resolved").longValue(), nullableDouble(value(row,"avg_ack")),
                nullableDouble(value(row,"avg_resolve")));
    }

    private MonitoringMetrics monitoringMetrics(Instant from, Instant to) {
        Map<String, Object> detection = jdbc.queryForMap("""
                SELECT COUNT(*) total,
                       COALESCE(SUM(event_type='SUSPECTED_FALL'),0) falls,
                       COALESCE(SUM(event_type='RESTRICTED_INTRUSION'),0) intrusions,
                       COALESCE(SUM(event_type='SOS_SIGNAL'),0) sos
                FROM `DetectionEvent` WHERE detected_at>=? AND detected_at<?
                """, Timestamp.from(from), Timestamp.from(to));
        Map<String, Object> crowd = jdbc.queryForMap("""
                SELECT COUNT(*) total,AVG(person_count) avg_persons,MAX(person_count) max_persons
                FROM `CrowdDensityRecord` WHERE captured_at>=? AND captured_at<?
                """, Timestamp.from(from), Timestamp.from(to));
        Object maximum = value(crowd,"max_persons");
        return new MonitoringMetrics(number(detection,"total").longValue(), number(detection,"falls").longValue(),
                number(detection,"intrusions").longValue(), number(detection,"sos").longValue(),
                number(crowd,"total").longValue(), nullableDouble(value(crowd,"avg_persons")),
                maximum instanceof Number n ? n.longValue() : null);
    }

    private List<ActivityBreakdown> activityByMap(Instant from, Instant to) {
        return activityBreakdown("""
                SELECT m.map_code code,CONCAT(m.map_name,' v',m.version_no) label,
                       SUM(a.detections) detections,SUM(a.crowds) crowds,SUM(a.alerts) alerts,
                       SUM(a.emergencies) emergencies,SUM(a.persons) persons
                FROM (
                    SELECT map_id,COUNT(*) detections,0 crowds,0 alerts,0 emergencies,0 persons
                      FROM `DetectionEvent` WHERE detected_at>=? AND detected_at<? GROUP BY map_id
                    UNION ALL
                    SELECT z.map_id,0,COUNT(*),0,0,COALESCE(SUM(c.person_count),0)
                      FROM `CrowdDensityRecord` c JOIN `Zone` z ON z.zone_id=c.zone_id
                      WHERE c.captured_at>=? AND c.captured_at<? GROUP BY z.map_id
                    UNION ALL
                    SELECT z.map_id,0,0,COUNT(*),0,0 FROM `Alert` a JOIN `Zone` z ON z.zone_id=a.zone_id
                      WHERE a.created_at>=? AND a.created_at<? GROUP BY z.map_id
                    UNION ALL
                    SELECT map_id,0,0,0,COUNT(*),0 FROM `EmergencyIncident`
                      WHERE occurred_at>=? AND occurred_at<? AND map_id IS NOT NULL GROUP BY map_id
                ) a JOIN `Map` m ON m.map_id=a.map_id
                GROUP BY m.map_id,m.map_code,m.map_name,m.version_no
                ORDER BY SUM(a.detections)+SUM(a.crowds)+SUM(a.alerts)+SUM(a.emergencies) DESC LIMIT 100
                """, from, to);
    }

    private List<ActivityBreakdown> activityByZone(Instant from, Instant to) {
        return activityBreakdown("""
                SELECT z.zone_code code,z.zone_name label,
                       SUM(a.detections) detections,SUM(a.crowds) crowds,SUM(a.alerts) alerts,
                       SUM(a.emergencies) emergencies,SUM(a.persons) persons
                FROM (
                    SELECT zone_id,COUNT(*) detections,0 crowds,0 alerts,0 emergencies,0 persons
                      FROM `DetectionEvent` WHERE detected_at>=? AND detected_at<? AND zone_id IS NOT NULL GROUP BY zone_id
                    UNION ALL
                    SELECT zone_id,0,COUNT(*),0,0,COALESCE(SUM(person_count),0)
                      FROM `CrowdDensityRecord` WHERE captured_at>=? AND captured_at<? GROUP BY zone_id
                    UNION ALL
                    SELECT zone_id,0,0,COUNT(*),0,0 FROM `Alert`
                      WHERE created_at>=? AND created_at<? AND zone_id IS NOT NULL GROUP BY zone_id
                    UNION ALL
                    SELECT e.zone_id,0,0,0,COUNT(*),0 FROM `EmergencyIncident` i
                      JOIN `Endpoint` e ON e.endpoint_id=i.endpoint_id
                      WHERE i.occurred_at>=? AND i.occurred_at<? GROUP BY e.zone_id
                ) a JOIN `Zone` z ON z.zone_id=a.zone_id
                GROUP BY z.zone_id,z.zone_code,z.zone_name
                ORDER BY SUM(a.detections)+SUM(a.crowds)+SUM(a.alerts)+SUM(a.emergencies) DESC LIMIT 100
                """, from, to);
    }

    private List<ActivityBreakdown> activityBreakdown(String sql, Instant from, Instant to) {
        Object[] args = {Timestamp.from(from),Timestamp.from(to),Timestamp.from(from),Timestamp.from(to),
                Timestamp.from(from),Timestamp.from(to),Timestamp.from(from),Timestamp.from(to)};
        return jdbc.query(sql, (rs,row) -> new ActivityBreakdown(rs.getString("code"), rs.getString("label"),
                rs.getLong("detections"), rs.getLong("crowds"), rs.getLong("alerts"),
                rs.getLong("emergencies"), rs.getLong("persons")), args);
    }

    private Breakdown breakdown(String key,String label,long total,long succeeded,long failed) {
        long terminal=succeeded+failed;
        return new Breakdown(key,label,total,succeeded,failed,terminal==0?0:round(succeeded*100.0/terminal));
    }

    private long count(String table, String timestamp, Instant from, Instant to) {
        Long value=jdbc.queryForObject("SELECT COUNT(*) FROM `"+table+"` WHERE `"+timestamp+"`>=? AND `"+timestamp+"`<?",
                Long.class,Timestamp.from(from),Timestamp.from(to));
        return value==null?0:value;
    }

    private Filter filter(Instant from,Instant to,UUID workflowId,UUID robotId,JobStatus status) {
        StringBuilder where=new StringBuilder(" FROM `Job` j JOIN `Workflow` w ON w.workflow_id=j.workflow_id LEFT JOIN `Robot` r ON r.robot_id=j.robot_id WHERE j.queued_at>=? AND j.queued_at<?");
        List<Object> args=new ArrayList<>(List.of(Timestamp.from(from),Timestamp.from(to)));
        if(workflowId!=null){where.append(" AND j.workflow_id=?");args.add(uuidBytes(workflowId));}
        if(robotId!=null){where.append(" AND j.robot_id=?");args.add(uuidBytes(robotId));}
        if(status!=null){where.append(" AND j.status=?");args.add(status.name());}
        return new Filter(where.toString(),args);
    }

    private List<JobDetail> queryJobDetails(Filter filter,int limit,int offset) {
        List<Object> args=new ArrayList<>(filter.args());args.add(limit);args.add(offset);
        return jdbc.query("SELECT j.job_id,j.job_code,j.status,w.workflow_code,w.version_no,r.robot_code,j.queued_at,"+
                        "j.started_at,j.ended_at,CASE WHEN j.started_at IS NOT NULL AND j.ended_at IS NOT NULL THEN " +
                        "TIMESTAMPDIFF(MICROSECOND,j.started_at,j.ended_at)/1000000.0 END duration_seconds,j.error_code"+
                        filter.fromWhere()+" ORDER BY j.queued_at DESC,j.job_id DESC LIMIT ? OFFSET ?",
                (rs,row)->new JobDetail(uuid(rs.getBytes("job_id")),rs.getString("job_code"),JobStatus.valueOf(rs.getString("status")),
                        rs.getString("workflow_code"),rs.getInt("version_no"),rs.getString("robot_code"),
                        instant(rs.getObject("queued_at")),instant(rs.getObject("started_at")),instant(rs.getObject("ended_at")),
                        nullableDouble(rs.getObject("duration_seconds")),rs.getString("error_code")),args.toArray());
    }

    private Object value(Map<String,Object> row,String key){for(var entry:row.entrySet())if(entry.getKey().equalsIgnoreCase(key))return entry.getValue();return null;}
    private Number number(Map<String,Object> row,String key){Object value=value(row,key);return value instanceof Number n?n:0;}
    private Double nullableDouble(Object value){return value instanceof Number number?round(number.doubleValue()):null;}
    private Double nullable(double value){return value<0?null:round(value);}
    private double round(double value){return Math.round(value*100.0)/100.0;}
    private byte[] uuidBytes(UUID id){return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    private UUID uuid(byte[] value){ByteBuffer b=ByteBuffer.wrap(value);return new UUID(b.getLong(),b.getLong());}
    private Instant instant(Object value){if(value==null)return null;if(value instanceof LocalDateTime v)return v.toInstant(ZoneOffset.UTC);if(value instanceof Timestamp v)return v.toInstant();throw new IllegalStateException("Unsupported timestamp");}
    private String csv(Object value){if(value==null)return "";String text=value.toString();if(!text.isEmpty()&&"=+-@".indexOf(text.charAt(0))>=0)text="'"+text;return '"'+text.replace("\"","\"\"")+'"';}
    private record Filter(String fromWhere,List<Object> args){}

    private static <E extends Enum<E>> Map<E, Long> zeroed(Class<E> enumType) {
        Map<E, Long> result = new EnumMap<>(enumType);
        for (E value : enumType.getEnumConstants()) result.put(value, 0L);
        return result;
    }
}

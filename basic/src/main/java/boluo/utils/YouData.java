package boluo.utils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.apache.spark.SparkContext;
import org.apache.spark.SparkException;
import org.apache.spark.scheduler.SparkListener;
import org.apache.spark.scheduler.SparkListenerTaskEnd;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.delta.util.SetAccumulator;
import org.apache.spark.sql.execution.datasources.jdbc.JdbcOptionsInWrite;
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils;
import org.apache.spark.sql.jdbc.JdbcDialect;
import org.apache.spark.sql.jdbc.JdbcDialects;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.apache.spark.sql.functions.*;

public class YouData {

	private static final Logger logger = LoggerFactory.getLogger(YouData.class);

	public static void replace(Dataset<Row> ds, String uri, JdbcOptionsInWrite opts, Column part) throws SQLException, ExecutionException, SparkException, InvocationTargetException {

		final double commit_factor = 0.1;
		final double max_allowed_factor = 0.3;
		final double max_memory_factor = 0.1;

		JdbcDialect dialect = JdbcDialects.get(opts.url());
		long max_allowed_packet = 4 * 1024 * 1024;
		long buffer_pool_size = 128 * 1024 * 1024;

		try (Connection conn = JdbcUtils.createConnectionFactory(opts).apply();
			 Statement statement = conn.createStatement()) {

			try (ResultSet packetRes = statement.executeQuery("show global variables like 'max_allowed_packet'")) {
				while (packetRes.next()) {
					max_allowed_packet = packetRes.getLong("Value");
				}
			}

			try (ResultSet bufferRes = statement.executeQuery("show global variables like 'innodb_buffer_pool_size'")) {
				while (bufferRes.next()) {
					buffer_pool_size = bufferRes.getLong("Value");
				}
			}
		}

		StructType schema = ds.schema();
		String sql_ = JdbcUtils.getInsertStatement(opts.table(), schema, Option.empty(), true, dialect);

		// sql拼接时不使用''的类型
		List<DataType> specialType = ImmutableList.of(DataTypes.BooleanType, DataTypes.LongType, DataTypes.IntegerType);

		MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
		MemoryUsage memoryUsage = memoryMXBean.getHeapMemoryUsage(); //堆内存使用情况
		long maxMemorySize = memoryUsage.getMax(); //最大可用内存

		SparkContext sparkContext = ds.sparkSession().sparkContext();
		int cores = sparkContext.defaultParallelism();
		int executorNum = sparkContext.getExecutorIds().size() + 1;

		long partNum = Math.min(
				Math.round(max_allowed_packet * max_allowed_factor),
				Math.round(maxMemorySize * max_memory_factor * executorNum / cores)
		);
		long bufferLength = Math.round(buffer_pool_size * commit_factor);
		Preconditions.checkArgument(partNum > 0, "partNum计算值<=0");

		Set<Object> lastSet = Sets.newHashSet();
		SetAccumulator<Object> collectionAc = new SetAccumulator<>();
		collectionAc.register(SparkSession.active().sparkContext(), Option.apply("setAc"), false);
		SparkSession.active().sparkContext().addSparkListener(new SparkListener() {

			public synchronized void onTaskEnd(SparkListenerTaskEnd taskEnd) {
				// 上次set中的值 lastSet, 本次set中的值 collectionAc.value()
				synchronized (lastSet) {
					Set<Object> diffSet;
					synchronized (collectionAc.value()) {
						diffSet = Sets.difference(collectionAc.value(), lastSet).immutableCopy();
					}
					if (!diffSet.isEmpty()) {
						for (Object i : diffSet) {
							System.out.println("监听器检测到差异..." + i);
						}
						lastSet.addAll(diffSet);
					}
				}
			}
		});
		StructType structType = ds.schema();
		if (Objects.nonNull(part)) {
			ds = ds.withColumn("partNum", part)
					.repartition(col("partNum"))
					.sortWithinPartitions(col("partNum"));
		} else {
			ds = ds.withColumn("partNum", expr("'0'"));
		}

		ds.foreachPartition(rows -> {
			try (Connection conn = JdbcUtils.createConnectionFactory(opts).apply();
				 Statement statement = conn.createStatement()) {
				conn.setAutoCommit(false);

				int numFields = schema.fields().length;
				int executeLength = 0;
				StringBuilder sb = null;
				String sqlPrefix = null;

				Object lastPartNum = null;
				while (rows.hasNext()) {
					Row row = rows.next();
					// 如果本行数据和上一行数据在同一张表, 直接插入数据, 如果本行数据和上一行数据不在同一张表, 提交上一次数据, 建表, 插入数据
					Object tmpPartNum = row.getAs("partNum");
					Preconditions.checkArgument(Objects.nonNull(tmpPartNum));

					if (!Objects.equals(tmpPartNum, lastPartNum)) {
						String newTableName = opts.table() + partSuffix(tmpPartNum);
						// 如果有该表则将该表中的数据清空
						String drop_table = String.format("drop table if exists %s", newTableName);
						statement.executeUpdate(drop_table);

						// 建表
						String col_sql = tableCol(structType);
						col_sql = col_sql.substring(0, col_sql.length() - 1);
						String create_table = String.format("create table if not exists %s(%s) DEFAULT CHARSET=utf8mb4", newTableName, col_sql);
						statement.executeUpdate(create_table);
						sqlPrefix = sql_.substring(0, sql_.indexOf("(?"));
						// 替换表名
						sqlPrefix = sqlPrefix.replace(sqlPrefix.substring(12, sqlPrefix.indexOf("(")), newTableName);

						if (!Objects.isNull(lastPartNum)) {
							// 记录执行掉的sql的长度
							executeLength += sb.length();
							statement.executeUpdate(sb.substring(0, sb.length() - 1));
							sb.setLength(0);
							sb.append(sqlPrefix);
						} else {
							sb = new StringBuilder((int) partNum + 1000);
							sb.append(sqlPrefix);
						}
						collectionAc.add(tmpPartNum);
						lastPartNum = tmpPartNum;
					}

					StringBuilder group = new StringBuilder("(");
					for (int i = 0; i < numFields; i++) {
						DataType type = schema.apply(i).dataType();
						if (row.isNullAt(i)) {
							// null值处理
							group.append("null,");
						} else if (specialType.contains(type)) {
							// 判断该类型数据是否需要''
							Object tmp = row.getAs(i);
							group.append(tmp).append(',');
						} else if (type == DataTypes.StringType) {
							// 如果该类型为字符串类型且包含', 则对'进行转义
							String tmp = row.getAs(i);
							group.append("'").append(tmp.replaceAll("'", "''")).append("',");
						} else {
							Object tmp = row.getAs(i);
							group.append("'").append(tmp).append("',");
						}
					}
					group.delete(group.length() - 1, group.length());
					group.append("),");
					sb.append(group);

					if (sb.length() * 2L >= partNum) {
						executeLength += sb.length();    // 每执行一次, 累计 + sb.length
						statement.executeLargeUpdate(sb.delete(sb.length() - 1, sb.length()).toString());
						sb.setLength(0);
						sb.append(sqlPrefix);
					}
					// 上面每执行一次, 累计 + max_allowed_packet, 累计加到缓冲池的阈值, 提交
					if (executeLength >= bufferLength) {
						logger.info("commit执行时间: {}", Instant.now());
						conn.commit();
						executeLength = 0;
					}
				}

				// 剩余还有未被执行的数据
				if (Objects.nonNull(sb) && sb.length() > sqlPrefix.length()) {
					String ex_sql = sb.substring(0, sb.length() - 1);
					statement.executeUpdate(ex_sql);
					sb.setLength(0);
					sb.append(sqlPrefix);
				}
				{
					logger.info("commit执行时间: {}", Instant.now());
					conn.commit();
				}
			}
		});
	}

	private static Object partSuffix(Object a) {
		if (Objects.equals(a, 0) || Objects.equals(a, "0")) {
			return "";
		}
		return String.format("_part%s", a);
	}

	private static String tableCol(StructType type) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < type.size(); i++) {
			String name = type.apply(i).name();
			String jdbcType = JdbcUtils.getCommonJDBCType(type.apply(i).dataType()).get().databaseTypeDefinition();
			sb.append("`").append(name).append("`").append(" ").append(jdbcType).append(",");
		}
		return sb.toString();
	}
}

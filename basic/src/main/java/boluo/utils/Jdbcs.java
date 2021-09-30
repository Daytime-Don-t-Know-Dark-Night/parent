package boluo.utils;

import com.google.common.base.*;
import com.google.common.collect.*;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.execution.datasources.jdbc.JDBCOptions;
import org.apache.spark.sql.execution.datasources.jdbc.JdbcOptionsInWrite;
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Function0;
import scala.Option;
import scala.Tuple2;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import scala.collection.immutable.Map$;

import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class Jdbcs {

	private static final Logger logger = LoggerFactory.getLogger(Jdbcs.class);

	@FunctionalInterface
	public interface ConnectConsumer {
		void accept(Statement stat, boolean exists) throws Exception;
	}

	public static JdbcOptionsInWrite options(String uri, String table, String... cols) {
		return options(uri, table, Arrays.asList(cols), null);
	}

	@SuppressWarnings("unchecked")
	public static JdbcOptionsInWrite options(String uri, String table, Iterable<String> cols, String opt) {
		Map<String, String> props = new Hashtable<>();
		if (uri.contains("postgresql")) {
			props.put(JDBCOptions.JDBC_DRIVER_CLASS(), "org.postgresql.Driver");
		} else {
			props.put(JDBCOptions.JDBC_DRIVER_CLASS(), "com.mysql.jdbc.Driver");
		}
		if (!Strings.isNullOrEmpty(uri)) {
			props.put(JDBCOptions.JDBC_URL(), uri);
		}
		if (!Strings.isNullOrEmpty(table)) {
			props.put(JDBCOptions.JDBC_TABLE_NAME(), table);
		}
		if (!Strings.isNullOrEmpty(opt)) {
			props.put(JDBCOptions.JDBC_CREATE_TABLE_OPTIONS(), opt);
		}
		if (Objects.nonNull(cols) && !Iterables.isEmpty(cols)) {
			props.put(JdbcOptionsInWrite.JDBC_CREATE_TABLE_COLUMN_TYPES(), String.join(",", cols));
		}
		Seq<Tuple2<String, String>> tuple2Seq = JavaConverters.mapAsScalaMapConverter(props).asScala().toSeq();
		return new JdbcOptionsInWrite((scala.collection.immutable.Map<String, String>) Map$.MODULE$.apply(tuple2Seq));
	}

}

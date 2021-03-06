package com.timeyang.athena.utill.jdbc;

import com.timeyang.athena.AthenaException;
import com.timeyang.athena.utill.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author https://github.com/chaokunyang
 */
public class JdbcUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcUtils.class);

    public static List<String> getAllTables(Connection connection) {
        List<String> tables = new ArrayList<>();
        try {
            DatabaseMetaData md = connection.getMetaData();
            ResultSet rs = md.getTables(null, null, "%", null);
            while (rs.next()) {
                tables.add(rs.getString(3));
            }
        } catch (SQLException e) {
            LOGGER.error("Can't get all tables", e);
            throw new AthenaException("Can't get all tables", e);
        }
        return tables;
    }

    public static boolean isTableExists(Connection connection, String tableName) {
        return getAllTables(connection).contains(tableName.toUpperCase());
    }

    /**
     * create table if table absent
     *
     * @param dataSource DataSource
     * @param tableName  table name
     * @param tableSql   table ddl sql
     * @return true if table created or false if table exists
     */
    public static boolean createTableIfAbsent(DataSource dataSource, String tableName, String tableSql) {
        try (Connection connection = dataSource.getConnection()) {
            return createTableIfAbsent(connection, tableName, tableSql);
        } catch (SQLException e) {
            throw new AthenaException("Can't get connection", e);
        }
    }

    /**
     * create table if table absent
     *
     * @param connection Connection
     * @param tableName  table name
     * @param tableSql   table ddl sql
     * @return true if table created or false if table exists
     */
    public static boolean createTableIfAbsent(Connection connection, String tableName, String tableSql) {
        if (!isTableExists(connection, tableName)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(tableSql);
            } catch (SQLException e) {
                e.printStackTrace();
                String msg = String.format("Can't create table %s, table sql: %s", tableName, tableSql);
                LOGGER.error(msg, e);
                throw new AthenaException(msg, e);
            }
            return true;
        }

        return false;
    }

    public static <T> List<T> query(Connection connection, String sql, RowMapper<T> rowMapper) {
        try (Statement statement = connection.createStatement()) {
            ResultSet rs = statement.executeQuery(sql);

            List<T> results = new ArrayList<>();
            while (rs.next()) {
                results.add(rowMapper.mapRow(rs, rs.getRow()));
            }
            rs.close();

            return results;
        } catch (SQLException e) {
            e.printStackTrace();
            throw new AthenaException("Execute sql failed, sql: " + sql, e);
        }
    }

    public static <T> List<T> query(DataSource dataSource, String sql, RowMapper<T> rowMapper) {
        try (Connection connection = dataSource.getConnection()) {
            return query(connection, sql, rowMapper);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new AthenaException("Can't get connection", e);
        }
    }

    public static <T> PagedResult<T> queryPage(DataSource dataSource, String tableName, Page page, RowMapper<T> rowMapper) {
        long offset = page.getPage() * page.getSize();
        String orderByClause = page.buildOrderByClause();
        String criterionClause = page.buildCriterionClause();
        String whereClause = " ";
        if (StringUtils.hasText(criterionClause)) {
            whereClause = String.format(" where %s ", criterionClause);
        }
        String sql = String.format("select * from %s %s %s offset %d ROWS FETCH NEXT %d ROWS ONLY",
                tableName, whereClause, orderByClause, offset, page.getSize());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            List<T> tables = query(connection, sql, rowMapper);

            ResultSet countRs = statement.executeQuery("SELECT COUNT(*) AS total FROM " + tableName);
            long total = 0;
            while(countRs.next())
                total = countRs.getLong("total");

            return new PagedResult<>(tables, total, page);
        } catch (SQLException e) {
            e.printStackTrace();
            String msg = String.format("Can't execute %s, sql: %s", tableName, sql);
            throw new AthenaException(msg, e);
        }
    }

    public interface RowMapper<T> {
        T mapRow(ResultSet rs, int rowNum) throws SQLException;
    }
}

package com.interviewer.data;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * 时间戳按 SQLAlchemy 的 SQLite 文本格式读写。
 *
 * <p>MyBatis 自带的处理器会走 {@code getObject(col, LocalDateTime.class)}，sqlite-jdbc
 * 把这些列当普通 TEXT，转不出来。必须自己接管，也正好保证与老库同格式。
 */
@MappedTypes(LocalDateTime.class)
public class UtcStampTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime value,
                                    JdbcType jdbcType) throws SQLException {
        ps.setString(i, UtcStamp.format(value));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return UtcStamp.parse(rs.getString(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return UtcStamp.parse(rs.getString(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return UtcStamp.parse(cs.getString(columnIndex));
    }
}

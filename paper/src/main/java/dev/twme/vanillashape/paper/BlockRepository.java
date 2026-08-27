package dev.twme.vanillashape.paper;

import dev.twme.vanillashape.common.SpecialBlock;
import java.io.Closeable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class BlockRepository implements Closeable {
    private final Connection connection;

    BlockRepository(final Path file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS special_blocks (
                    world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                    shape TEXT NOT NULL, material TEXT NOT NULL, facing TEXT NOT NULL,
                    corner TEXT NOT NULL, flags INTEGER NOT NULL,
                    PRIMARY KEY (world, x, y, z)
                )
                """);
        }
    }

    List<SpecialBlock> loadAll() throws SQLException {
        final var result = new ArrayList<SpecialBlock>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM special_blocks")) {
            while (rows.next()) {
                result.add(new SpecialBlock(
                        rows.getString("world"), rows.getInt("x"), rows.getInt("y"), rows.getInt("z"),
                        dev.twme.vanillashape.common.ShapeType.valueOf(rows.getString("shape")),
                        rows.getString("material"),
                        dev.twme.vanillashape.common.Direction.valueOf(rows.getString("facing")),
                        dev.twme.vanillashape.common.CornerShape.valueOf(rows.getString("corner")),
                        rows.getInt("flags")));
            }
        }
        return result;
    }

    void upsert(final SpecialBlock block) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO special_blocks(world,x,y,z,shape,material,facing,corner,flags)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(world,x,y,z) DO UPDATE SET shape=excluded.shape,
                    material=excluded.material,facing=excluded.facing,
                    corner=excluded.corner,flags=excluded.flags
                """)) {
            statement.setString(1, block.world());
            statement.setInt(2, block.x()); statement.setInt(3, block.y()); statement.setInt(4, block.z());
            statement.setString(5, block.shape().name()); statement.setString(6, block.material());
            statement.setString(7, block.facing().name()); statement.setString(8, block.corner().name());
            statement.setInt(9, block.flags());
            statement.executeUpdate();
        }
    }

    void remove(final String world, final BlockPosKey pos) throws SQLException {
        try (var statement = connection.prepareStatement(
                "DELETE FROM special_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
            statement.setString(1, world);
            statement.setInt(2, pos.x()); statement.setInt(3, pos.y()); statement.setInt(4, pos.z());
            statement.executeUpdate();
        }
    }

    @Override public void close() {
        try { connection.close(); } catch (final SQLException ignored) {}
    }
}

package com.pingcap;

import com.mysql.cj.jdbc.MysqlDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Data access object used by 'ExampleDataSource'.
 * Example for CURD and bulk insert
 */
public class PlayerDAO {
    private final MysqlDataSource ds;
    private final Random rand = new Random();

    PlayerDAO(MysqlDataSource ds) {
        this.ds = ds;
    }

    /**
     * create players by passing in a List of PlayerBean
     *
     * @param players will create players list
     * @return The number of create accounts
     */
    public int createPlayers(List<PlayerBean> players){
        int rows = 0;

        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = ds.getConnection();
            preparedStatement = connection.prepareStatement("INSERT INTO player (id, coins, goods) VALUES (?, ?, ?)");
        } catch (SQLException e) {
            System.out.printf("[createPlayers] ERROR: { state => %s, cause => %s, message => %s }\n",
                    e.getSQLState(), e.getCause(), e.getMessage());
            e.printStackTrace();

            return -1;
        }

        try {
            for (PlayerBean player : players) {
                preparedStatement.setString(1, player.getId());
                preparedStatement.setInt(2, player.getCoins());
                preparedStatement.setInt(3, player.getGoods());

                preparedStatement.execute();
                rows += preparedStatement.getUpdateCount();
            }
        } catch (SQLException e) {
            System.out.printf("[createPlayers] ERROR: { state => %s, cause => %s, message => %s }\n",
                    e.getSQLState(), e.getCause(), e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        System.out.printf("\n[createPlayers]:\n    '%s'\n", preparedStatement);
        return rows;
    }

    /**
     * buy goods and transfer funds between one player and another in one transaction
     * @param sellId sell player id
     * @param buyId buy player id
     * @param amount goods amount, if sell player has not enough goods, the trade will break
     * @param price price should pay, if buy player has not enough coins, the trade will break
     *
     * @return The number of effected players (int)
     */
    public int buyGoods(String sellId, String buyId, Integer amount, Integer price) {
        int effectPlayers = 0;

        Connection connection = null;
        try {
            connection = ds.getConnection();
        } catch (SQLException e) {
            System.out.printf("[buyGoods] ERROR: { state => %s, cause => %s, message => %s }\n",
                    e.getSQLState(), e.getCause(), e.getMessage());
            e.printStackTrace();
            return effectPlayers;
        }

        try {
            connection.setAutoCommit(false);

            PreparedStatement playerQuery = connection.prepareStatement("SELECT * FROM player WHERE id=? OR id=? FOR UPDATE");
            playerQuery.setString(1, sellId);
            playerQuery.setString(2, buyId);
            playerQuery.execute();

            PlayerBean sellPlayer = null;
            PlayerBean buyPlayer = null;

            ResultSet playerQueryResultSet = playerQuery.getResultSet();
            while (playerQueryResultSet.next()) {
                PlayerBean player =  new PlayerBean(
                        playerQueryResultSet.getString("id"),
                        playerQueryResultSet.getInt("coins"),
                        playerQueryResultSet.getInt("goods")
                );

                System.out.println("\n[buyGoods]:\n    'check goods and coins enough'");
                System.out.printf("    %-8s => %10s\n", "id", player.getId());
                System.out.printf("    %-8s => %10s\n", "coins", player.getCoins());
                System.out.printf("    %-8s => %10s\n", "goods", player.getGoods());

                if (sellId.equals(player.getId())) {
                    sellPlayer = player;
                } else {
                    buyPlayer = player;
                }
            }

            if (sellPlayer == null || buyPlayer == null) {
                throw new SQLException("player not exist.");
            }

            if (sellPlayer.getGoods().compareTo(amount) < 0) {
                throw new SQLException(String.format("sell player %s goods not enough.", sellId));
            }

            if (buyPlayer.getCoins().compareTo(price) < 0) {
                throw new SQLException(String.format("buy player %s coins not enough.", buyId));
            }

            PreparedStatement transfer = connection.prepareStatement("UPDATE player set goods = goods + ?, coins = coins + ? WHERE id=?");
            transfer.setInt(1, -amount);
            transfer.setInt(2, price);
            transfer.setString(3, sellId);
            transfer.execute();
            effectPlayers += transfer.getUpdateCount();

            transfer.setInt(1, amount);
            transfer.setInt(2, -price);
            transfer.setString(3, buyId);
            transfer.execute();
            effectPlayers += transfer.getUpdateCount();

            connection.commit();

            System.out.println("\n[buyGoods]:\n    'trade success'");
        } catch (SQLException e) {
            System.out.printf("[buyGoods] ERROR: { state => %s, cause => %s, message => %s }\n",
                    e.getSQLState(), e.getCause(), e.getMessage());

            try {
                System.out.println("[buyGoods] Rollback");

                connection.rollback();
            } catch (SQLException ex) {
                // do nothing
            }
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                // do nothing
            }
        }

        return effectPlayers;
    }

    /**
     * get the player info by id.
     *
     * @param id player id
     * @return the player of this id
     */
    public PlayerBean getPlayer(String id) {
        PlayerBean player = null;

        try (Connection connection = ds.getConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM player WHERE id = ?");
            preparedStatement.setString(1, id);
            preparedStatement.execute();

            ResultSet res = preparedStatement.executeQuery();
            if(!res.next()) {
                System.out.printf("No players in the table with id %s", id);
            } else {
                player = new PlayerBean(res.getString("id"), res.getInt("coins"), res.getInt("goods"));
            }
        } catch (SQLException e) {
            System.out.printf("PlayerDAO.getPlayer ERROR: { state => %s, cause => %s, message => %s }\n",
                    e.getSQLState(), e.getCause(), e.getMessage());
        }

        return player;
    }

    /**
     * Insert randomized account data (id, coins, goods) using the JDBC fast path for
     * bulk inserts.  The fastest way to get data into TiDB is using the
     * TiDB Lightning(https://docs.pingcap.com/zh/tidb/v4.0/tidb-lightning-overview).
     * However, if you must bulk insert from the application using INSERT SQL, the best
     * option is the method shown here. It will require the following:
     *
     *    Add `rewriteBatchedStatements=true` to your JDBC connection settings.
     *    Setting rewriteBatchedStatements to true now causes CallableStatements
     *    with batched arguments to be re-written in the form "CALL (...); CALL (...); ..."
     *    to send the batch in as few client/server round trips as possible.
     *    https://dev.mysql.com/doc/relnotes/connector-j/5.1/en/news-5-1-3.html
     *
     *    You can see the `rewriteBatchedStatements` param effect logic at
     *    implement function: `com.mysql.cj.jdbc.StatementImpl.executeBatchUsingMultiQueries`
     *
     * @param total add players amount
     * @param batchSize bulk insert size for per batch
     *
     * @return The number of new accounts inserted
     */
    public int bulkInsertRandomPlayers(Integer total, Integer batchSize) {
        int totalNewPlayers = 0;

        try (Connection connection = ds.getConnection()) {
            // We're managing the commit lifecycle ourselves, so we can
            // control the size of our batch inserts.
            connection.setAutoCommit(false);

            // In this example we are adding 500 rows to the database,
            // but it could be any number.  What's important is that
            // the batch size is 128.
            try (PreparedStatement pstmt = connection.prepareStatement("INSERT INTO player (id, coins, goods) VALUES (?, ?, ?)")) {
                for (int i=0; i<=(total/batchSize);i++) {
                    for (int j=0; j<batchSize; j++) {
                        String id = UUID.randomUUID().toString();
                        pstmt.setString(1, id);
                        pstmt.setInt(2, rand.nextInt(10000));
                        pstmt.setInt(3, rand.nextInt(10000));
                        pstmt.addBatch();
                    }

                    int[] count = pstmt.executeBatch();
                    totalNewPlayers += count.length;
                    System.out.printf("\nPlayerDAO.bulkInsertRandomPlayers:\n    '%s'\n", pstmt);
                    System.out.printf("    => %s row(s) updated in this batch\n", count.length);
                }
                connection.commit();
            } catch (SQLException e) {
                System.out.printf("PlayerDAO.bulkInsertRandomPlayers ERROR: { state => %s, cause => %s, message => %s }\n",
                        e.getSQLState(), e.getCause(), e.getMessage());
            }
        } catch (SQLException e) {
            System.out.printf("PlayerDAO.bulkInsertRandomPlayers ERROR: { state => %s, cause => %s, message => %s }\n",
                    e.getSQLState(), e.getCause(), e.getMessage());
        }
        return totalNewPlayers;
    }


    /**
     * print a subset of players from the data storeby limit.
     *
     * @param limit print max size
     */
    public void printPlayers(Integer limit) {
        try (Connection connection = ds.getConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM player LIMIT ?");
            preparedStatement.setInt(1, limit);
            preparedStatement.execute();

            ResultSet res = preparedStatement.executeQuery();
            while (!res.next()) {
                System.out.println("\n[printPlayers]:");
                System.out.printf("    %-8s => %10s\n", "id", res.getString("id"));
                System.out.printf("    %-8s => %10s\n", "coins", res.getInt("coins"));
                System.out.printf("    %-8s => %10s\n", "goods", res.getInt("goods"));
            }
        } catch (SQLException e) {
            System.out.printf("PlayerDAO.printPlayers ERROR: { state => %s, cause => %s, message => %s }\n",
                    e.getSQLState(), e.getCause(), e.getMessage());
        }
    }


    /**
     * count players from the data store.
     *
     * @return all players count
     */
    public int countPlayers() {
        int count = 0;

        try (Connection connection = ds.getConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement("SELECT count(*) FROM player");
            preparedStatement.execute();

            ResultSet res = preparedStatement.executeQuery();
            if(res.next()) {
                count = res.getInt(1);
            }
        } catch (SQLException e) {
            System.out.printf("PlayerDAO.countPlayers ERROR: { state => %s, cause => %s, message => %s }\n",
                    e.getSQLState(), e.getCause(), e.getMessage());
        }

        return count;
    }
}


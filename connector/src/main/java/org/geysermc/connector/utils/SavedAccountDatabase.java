/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.utils;

import it.unimi.dsi.fastutil.Pair;
import lombok.SneakyThrows;

import java.sql.*;

public final class SavedAccountDatabase {
    private static SavedAccountDatabase instance;
    Connection db;

    private SavedAccountDatabase() {
        connect();
        createTableIfNotExists();
    }
    public static SavedAccountDatabase getInstance() {
        if (instance == null) {
            instance = new SavedAccountDatabase();
        }
        return instance;
    }

    private void createTableIfNotExists() {
        String sqlCreateTable =
            "CREATE TABLE IF NOT EXISTS SavedAccounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "username TEXT," +
                "password TEXT," +
                "deviceID TEXT" +
        ")";
        performSQLStatement(sqlCreateTable);
    }

    private void connect() {
        Connection conn = null;
        try {
            // create a connection to the database
            conn = DriverManager.getConnection(Constants.SAVED_ACCOUNTS_DB);
            System.out.println("Connection to SQLite has been established.");
        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(1);
        }
        this.db = conn;
    }

    private ResultSet performSQLQuery(String sql) {
        try {
            if (db.isClosed()) {
                connect();
            }

            PreparedStatement stmt = db.prepareStatement(sql);

            return stmt.executeQuery();
        }
        catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void performSQLStatement(String sql) {
        try {
            if (db.isClosed()) {
                connect();
            }

            PreparedStatement stmt = db.prepareStatement(sql);
            stmt.execute();

        }
        catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveNewUserAccount(String username, String password, String deviceID) {
        final String sqlCheckIfDeviceAlreadyHasAccount = String.format(
            "SELECT COUNT(1) FROM SavedAccounts WHERE deviceID=\"%s\"",
            deviceID
        );

        final String sqlAddAccount = String.format(
            "INSERT INTO SavedAccounts (username,password,deviceID) VALUES (\"%s\",\"%s\",\"%s\")",
            username,
            password,
            deviceID
        );

        ResultSet rs = performSQLQuery(sqlCheckIfDeviceAlreadyHasAccount);
        try {
            if (rs.getInt(1) == 1) {
                System.out.println("Account already exists with this deviceID, overwriting...");
            }
            performSQLStatement(sqlAddAccount);
            System.out.println("Saved new users account details");

        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    public boolean checkIfUserHasSavedAccount(String deviceID) {
        final String sqlCheckIfUserHasSavedAccount = String.format(
          "SELECT COUNT(1) FROM SavedAccounts WHERE deviceID=\"%s\"",
                deviceID
        );

        try {
            return performSQLQuery(sqlCheckIfUserHasSavedAccount).getInt(1) == 1;
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        return false;
    }

    public Pair<String, String> getSavedAccountDetails(String deviceID) {
        final String sqlGetAccount = String.format(
          "SELECT username,password FROM SavedAccounts WHERE deviceID=\"%s\"",
                deviceID
        );

        ResultSet results = performSQLQuery(sqlGetAccount);

        return new Pair<String, String>() {
            @SneakyThrows
            @Override
            public String left() {
                return results.getString("username");
            }

            @SneakyThrows
            @Override
            public String right() {
                return results.getString("password");
            }
        };
    }


}

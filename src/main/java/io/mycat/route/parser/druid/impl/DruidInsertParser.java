package io.mycat.route.parser.druid.impl;

import java.sql.SQLNonTransientException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement.ValuesClause;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;

import io.mycat.MycatServer;
import io.mycat.backend.mysql.nio.handler.FetchStoreNodeOfChildTableHandler;
import io.mycat.config.model.SchemaConfig;
import io.mycat.config.model.TableConfig;
import io.mycat.meta.protocol.MyCatMeta.TableMeta;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;
import io.mycat.route.function.AbstractPartitionAlgorithm;
import io.mycat.route.parser.druid.MycatSchemaStatVisitor;
import io.mycat.route.util.RouterUtil;
import io.mycat.server.parser.ServerParse;
import io.mycat.server.util.SchemaUtil;
import io.mycat.server.util.SchemaUtil.SchemaInfo;
import io.mycat.util.StringUtil;

public class DruidInsertParser extends DefaultDruidParser {
	@Override
	public void visitorParse(RouteResultset rrs, SQLStatement stmt, MycatSchemaStatVisitor visitor) throws SQLNonTransientException {
		
	}
	
	/**
	 * 考虑因素：isChildTable、批量、是否分片
	 */
	@Override
	public void statementParse(SchemaConfig schema, RouteResultset rrs, SQLStatement stmt) throws SQLNonTransientException {
		MySqlInsertStatement insert = (MySqlInsertStatement)stmt;
		String schemaName = schema == null ? null : schema.getName();
		SchemaInfo schemaInfo = SchemaUtil.getSchemaInfo(schemaName, insert.getTableSource());
		if(schemaInfo == null){
			String msg = "No MyCAT Database selected Or Define";
			throw new SQLNonTransientException(msg);
		}
		schema = schemaInfo.schemaConfig;
		String tableName = schemaInfo.table;
		insert.setTableSource(new SQLIdentifierExpr(tableName));
		ctx.addTable(tableName);
		if(RouterUtil.isNoSharding(schema,tableName)) {//整个schema都不分库或者该表不拆分
			RouterUtil.routeForTableMeta(rrs, schema, tableName, rrs.getStatement());
			rrs.setFinishedRoute(true);
			return;
		}

		TableConfig tc = schema.getTables().get(tableName);
		if(tc == null) {
			String msg = "can't find table [" + tableName + "] define in schema:" + schema.getName();
			throw new SQLNonTransientException(msg);
		} else {
			//childTable的insert直接在解析过程中完成路由
			if (tc.isChildTable()) {
				parserChildTable(schema, rrs, tableName, insert);
				return;
			}
			
			String partitionColumn = tc.getPartitionColumn();
			
			if(partitionColumn != null) {
				//批量insert
				if(isMultiInsert(insert)) {
					parserBatchInsert(schema, rrs, partitionColumn, tableName, insert);
				} else {
					parserSingleInsert(schema, rrs, partitionColumn, tableName, insert);
				}
				
			}
		}
	}
	
	/**
	 * 寻找joinKey的索引
	 * @param columns
	 * @param joinKey
	 * @return -1表示没找到，>=0表示找到了
	 */
	private int getJoinKeyIndex(List<SQLExpr> columns, String joinKey) {
		for(int i = 0; i < columns.size(); i++) {
			String col = StringUtil.removeBackquote(columns.get(i).toString()).toUpperCase();
			if(col.equals(joinKey)) {
				return i;
			}
		}
		return -1;
	}
	
	/**
	 * 是否为批量插入：insert into ...values (),()...或 insert into ...select.....
	 * @param insertStmt
	 * @return
	 */
	private boolean isMultiInsert(MySqlInsertStatement insertStmt) {
		return (insertStmt.getValuesList() != null && insertStmt.getValuesList().size() > 1) || insertStmt.getQuery() != null;
	}
	
	private RouteResultset parserChildTable(SchemaConfig schema, RouteResultset rrs,
			String tableName, MySqlInsertStatement insertStmt) throws SQLNonTransientException {
		TableConfig tc = schema.getTables().get(tableName);
		
		String joinKey = tc.getJoinKey();
		int joinKeyIndex = getJoinKeyIndex(insertStmt.getColumns(), joinKey);
		if(joinKeyIndex == -1) {
			String inf = "joinKey not provided :" + tc.getJoinKey()+ "," + insertStmt;
			LOGGER.warn(inf);
			throw new SQLNonTransientException(inf);
		}
		if(isMultiInsert(insertStmt)) {
			String msg = "ChildTable multi insert not provided" ;
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}
		
		String joinKeyVal = insertStmt.getValues().getValues().get(joinKeyIndex).toString();

		
		String sql = insertStmt.toString();
		
		// try to route by ER parent partion key
		RouteResultset theRrs = RouterUtil.routeByERParentKey(null,schema, ServerParse.INSERT,sql, rrs, tc,joinKeyVal);
		if (theRrs != null) {
			rrs.setFinishedRoute(true);
			return theRrs;
		}

		// route by sql query root parent's datanode
		String findRootTBSql = tc.getLocateRTableKeySql().toLowerCase() + joinKeyVal;
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("find root parent's node sql "+ findRootTBSql);
		}
		FetchStoreNodeOfChildTableHandler fetchHandler = new FetchStoreNodeOfChildTableHandler();
		String dn = fetchHandler.execute(schema.getName(),findRootTBSql, tc.getRootParent().getDataNodes());
		if (dn == null) {
			throw new SQLNonTransientException("can't find (root) parent sharding node for sql:"+ sql);
		}
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("found partion node for child table to insert "+ dn + " sql :" + sql);
		}
		return RouterUtil.routeToSingleNode(rrs, dn, sql);
	}
	
	/**
	 * 单条insert（非批量）
	 * @param schema
	 * @param rrs
	 * @param partitionColumn
	 * @param tableName
	 * @param insertStmt
	 * @throws SQLNonTransientException
	 */
	private void parserSingleInsert(SchemaConfig schema, RouteResultset rrs, String partitionColumn,
			String tableName, MySqlInsertStatement insertStmt) throws SQLNonTransientException {
		int shardingColIndex = getShardingColIndex(schema, insertStmt, partitionColumn);
		if(shardingColIndex == -1) {
			String msg = "bad insert sql (sharding column:"+ partitionColumn + " not provided," + insertStmt;
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}

		SQLExpr valueExpr = insertStmt.getValues().getValues().get(shardingColIndex);
		String shardingValue = null;
		if(valueExpr instanceof SQLIntegerExpr) {
			SQLIntegerExpr intExpr = (SQLIntegerExpr)valueExpr;
			shardingValue = intExpr.getNumber() + "";
		} else if (valueExpr instanceof SQLCharExpr) {
			SQLCharExpr charExpr = (SQLCharExpr)valueExpr;
			shardingValue = charExpr.getText();
		}
		TableConfig tableConfig = schema.getTables().get(tableName);
		AbstractPartitionAlgorithm algorithm = tableConfig.getRule().getRuleAlgorithm();
		Integer nodeIndex = algorithm.calculate(shardingValue);
		//没找到插入的分片
		if(nodeIndex == null) {
			String msg = "can't find any valid datanode :" + tableName 
					+ " -> " + partitionColumn + " -> " + shardingValue;
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}
		RouteResultsetNode[] nodes = new RouteResultsetNode[1];
		nodes[0] = new RouteResultsetNode(tableConfig.getDataNodes().get(nodeIndex), rrs.getSqlType(), insertStmt.toString());
		nodes[0].setSource(rrs);

		// insert into .... on duplicateKey 
		//such as :INSERT INTO TABLEName (a,b,c) VALUES (1,2,3) ON DUPLICATE KEY UPDATE b=VALUES(b); 
		//INSERT INTO TABLEName (a,b,c) VALUES (1,2,3) ON DUPLICATE KEY UPDATE c=c+1;
		if(insertStmt.getDuplicateKeyUpdate() != null) {
			List<SQLExpr> updateList = insertStmt.getDuplicateKeyUpdate();
			for(SQLExpr expr : updateList) {
				SQLBinaryOpExpr opExpr = (SQLBinaryOpExpr)expr;
				String column = StringUtil.removeBackquote(opExpr.getLeft().toString().toUpperCase());
				if(column.equals(partitionColumn)) {
					String msg = "Sharding column can't be updated: " + tableName + " -> " + partitionColumn;
					LOGGER.warn(msg);
					throw new SQLNonTransientException(msg);
				}
			}
		}
		rrs.setNodes(nodes);
		rrs.setFinishedRoute(true);
	}
	
	/**
	 * insert into .... select .... 或insert into table() values (),(),....
	 * @param schema
	 * @param rrs
	 * @param insertStmt
	 * @throws SQLNonTransientException
	 */
	private void parserBatchInsert(SchemaConfig schema, RouteResultset rrs, String partitionColumn, 
			String tableName, MySqlInsertStatement insertStmt) throws SQLNonTransientException {
		//insert into table() values (),(),....
		if(insertStmt.getValuesList().size() > 1) {
			//字段列数
			int columnNum = getTableColumns(schema, insertStmt);
			int shardingColIndex = getShardingColIndex(schema, insertStmt, partitionColumn);
			if(shardingColIndex == -1) {
				String msg = "bad insert sql (sharding column:"+ partitionColumn + " not provided," + insertStmt;
				LOGGER.warn(msg);
				throw new SQLNonTransientException(msg);
			} else {
				List<ValuesClause> valueClauseList = insertStmt.getValuesList();
				
				Map<Integer,List<ValuesClause>> nodeValuesMap = new HashMap<Integer,List<ValuesClause>>();
				TableConfig tableConfig = schema.getTables().get(tableName);
				AbstractPartitionAlgorithm algorithm = tableConfig.getRule().getRuleAlgorithm();
				for(ValuesClause valueClause : valueClauseList) {
					if(valueClause.getValues().size() != columnNum) {
						String msg = "bad insert sql columnSize != valueSize:"
					             + columnNum + " != " + valueClause.getValues().size() 
					             + "values:" + valueClause;
						LOGGER.warn(msg);
						throw new SQLNonTransientException(msg);
					}
					SQLExpr expr = valueClause.getValues().get(shardingColIndex);
					String shardingValue = null;
					if(expr instanceof SQLIntegerExpr) {
						SQLIntegerExpr intExpr = (SQLIntegerExpr)expr;
						shardingValue = intExpr.getNumber() + "";
					} else if (expr instanceof SQLCharExpr) {
						SQLCharExpr charExpr = (SQLCharExpr)expr;
						shardingValue = charExpr.getText();
					}
					
					Integer nodeIndex = algorithm.calculate(shardingValue);
					//没找到插入的分片
					if(nodeIndex == null) {
						String msg = "can't find any valid datanode :" + tableName 
								+ " -> " + partitionColumn + " -> " + shardingValue;
						LOGGER.warn(msg);
						throw new SQLNonTransientException(msg);
					}
					if(nodeValuesMap.get(nodeIndex) == null) {
						nodeValuesMap.put(nodeIndex, new ArrayList<ValuesClause>());
					}
					nodeValuesMap.get(nodeIndex).add(valueClause);
				}
				
				RouteResultsetNode[] nodes = new RouteResultsetNode[nodeValuesMap.size()];
				int count = 0;
				for(Map.Entry<Integer,List<ValuesClause>> node : nodeValuesMap.entrySet()) {
					Integer nodeIndex = node.getKey();
					List<ValuesClause> valuesList = node.getValue();
					insertStmt.setValuesList(valuesList);
					nodes[count] = new RouteResultsetNode(tableConfig.getDataNodes().get(nodeIndex),
							rrs.getSqlType(),insertStmt.toString());
					nodes[count++].setSource(rrs);

				}
				rrs.setNodes(nodes);
				rrs.setFinishedRoute(true);
			}
		} else if(insertStmt.getQuery() != null) { // insert into .... select ....
			String msg = "TODO:insert into .... select .... not supported!";
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}
	}
	
	/**
	 * 寻找拆分字段在 columnList中的索引
	 * @param insertStmt
	 * @param partitionColumn
	 * @return
	 */
	private int getShardingColIndex(SchemaConfig schema, MySqlInsertStatement insertStmt, String partitionColumn) {
		int shardingColIndex = -1;
		if (insertStmt.getColumns() == null || insertStmt.getColumns().size() == 0) {
			String table = StringUtil.removeBackquote(insertStmt.getTableName().getSimpleName()).toUpperCase();
			TableMeta tbMeta = MycatServer.getInstance().getTmManager().getTableMeta(schema.getName(), table);
			if (tbMeta != null) {
				for (int i = 0; i < tbMeta.getColumnsCount(); i++) {
					if (partitionColumn.equalsIgnoreCase(StringUtil.removeBackquote(tbMeta.getColumns(i).getName()))) {
						return i;
					}
				}
			}
			return shardingColIndex;
		}
		for (int i = 0; i < insertStmt.getColumns().size(); i++) {
			if (partitionColumn.equalsIgnoreCase(StringUtil.removeBackquote(insertStmt.getColumns().get(i).toString()))) {
				return i;
			}
		}
		return shardingColIndex;
	}

	private int getTableColumns(SchemaConfig schema, MySqlInsertStatement insertStmt) throws SQLNonTransientException {
		if (insertStmt.getColumns() == null || insertStmt.getColumns().size() == 0) {
			String table = StringUtil.removeBackquote(insertStmt.getTableName().getSimpleName()).toUpperCase();
			TableMeta tbMeta = MycatServer.getInstance().getTmManager().getTableMeta(schema.getName(), table);
			if (tbMeta == null) {
				String msg = "can't find table [" + table + "] define in schema:" + schema.getName();
				LOGGER.warn(msg);
				throw new SQLNonTransientException(msg);
			}
			return tbMeta.getColumnsCount();
		} else {
			return insertStmt.getColumns().size();
		}
	}
}
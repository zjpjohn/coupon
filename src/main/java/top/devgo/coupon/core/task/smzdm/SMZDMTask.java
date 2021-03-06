package top.devgo.coupon.core.task.smzdm;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import top.devgo.coupon.core.page.Page;
import top.devgo.coupon.core.task.Task;
import top.devgo.coupon.core.task.TaskBase;
import top.devgo.coupon.utils.DateUtil;
import top.devgo.coupon.utils.JsonUtil;
import top.devgo.coupon.utils.MongoDBUtil;
import top.devgo.coupon.utils.TextUtil;

public class SMZDMTask extends TaskBase {
	private static Logger logger = Logger.getLogger(SMZDMTask.class.getName());

	private String timesort;
	private String stopDate;
	private String mongoURI;
	private String dbName;
	private boolean updateRecord;//是否更新已有的记录
	private boolean fetchComment;//是否抓取相关的评论
	
	/**
	 * 初始化smzdm首页抓取任务
	 * @param priority
	 * @param timesort 时间戳
	 * @param stopDate 抓取结束日期 "2015-12-01 00:00:00"
	 * @param mongoURI "mongodb://localhost:27017,localhost:27018,localhost:27019"
	 * @param dbName
	 * @param updateRecord 是否更新已有的记录
	 * @param fetchComment 是否抓取相关的评论
	 */
	public SMZDMTask(int priority, String timesort, String stopDate, String mongoURI, String dbName, 
			boolean updateRecord, boolean fetchComment) {
		super(priority);
		this.timesort = timesort;
		this.stopDate = stopDate;
		this.mongoURI = mongoURI;
		this.dbName = dbName;
		this.updateRecord = updateRecord;
		this.fetchComment = fetchComment;
	}
	
	/**
	 * 初始化smzdm首页抓取任务
	 * @param timesort 时间戳
	 * @param mongoURI "mongodb://localhost:27017,localhost:27018,localhost:27019"
	 * @param dbName
	 * @param updateRecord 是否更新已有的记录
	 * @param fetchComment 是否抓取相关的评论
	 */
	public SMZDMTask(String timesort, String mongoURI, String dbName, boolean updateRecord, boolean fetchComment) {
		this(timesort, DateUtil.getDateString(DateUtil.getBeginOfDay(new Date())), mongoURI, dbName, updateRecord, fetchComment);
	}
	
	/**
	 * 初始化smzdm首页抓取任务
	 * @param timesort 时间戳
	 * @param stopDate 抓取结束日期 "2015-12-01 00:00:00"
	 * @param mongoURI "mongodb://localhost:27017,localhost:27018,localhost:27019"
	 * @param dbName
	 * @param updateRecord 是否更新已有的记录
	 * @param fetchComment 是否抓取相关的评论
	 */
	public SMZDMTask(String timesort, String stopDate, String mongoURI, String dbName, boolean updateRecord, boolean fetchComment) {
		this(1, timesort, stopDate, mongoURI, dbName,  updateRecord, fetchComment);
	}

	
	public HttpUriRequest buildRequest() {
		HttpUriRequest request = RequestBuilder
				.get()
				.setUri("http://www.smzdm.com/json_more")
				.addParameter("timesort", this.timesort)
				.setHeader("Host", "www.smzdm.com")
				.setHeader("Referer", "http://www.smzdm.com/")
				.setHeader(
						"User-Agent",
						"Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/47.0.2526.80 Safari/537.36")
				.setHeader("X-Requested-With", "XMLHttpRequest").build();
		return request;
	}


	@Override
	protected void process(Page page) {
		String htmlStr = getHtmlStr(page);
		htmlStr = TextUtil.decodeUnicode(htmlStr);
		htmlStr = JsonUtil.formateDoubleQuotationMarks(htmlStr);
		List<Map<String, String>> data = extractData(htmlStr);
		//规格化data
		for (Map<String, String> d : data) {
			//格式化日期
			try {
				String date = DateUtil.getDateString(DateUtil.getDateFromString(d.get("article_date")));
				d.put("article_date_full", date);
			} catch (ParseException e) {
				e.printStackTrace();
			}
			
			d.put("create_date", DateUtil.getDateString(new Date()));
		}
		page.setData(data);
		
		//指定主键
		for (Map<String, String> d : data) {
			d.put("_id", d.get("article_id"));
		}
		MongoDBUtil.insertMany(data, this.updateRecord, this.mongoURI, this.dbName, "smzdm_data");
	}

	private List<Map<String, String>> extractData(String jsonString){
		List<Map<String, String>> data = new ArrayList<Map<String,String>>();
		ObjectMapper mapper = new ObjectMapper(); // can reuse, share globally
		mapper.configure(Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true) ;  
		mapper.configure(Feature.ALLOW_SINGLE_QUOTES, true);
		JsonNode root = null;
		try {
			root = mapper.readTree(jsonString);
		} catch (IOException e) {
			if (e instanceof JsonParseException) {
				logger.info("jackson json解析报错: "+e+" ，尝试使用 JsonUtil.formateJsonArrays 解析。");
				try {
					String after = JsonUtil.formateJsonArrays(jsonString);
					root = mapper.readTree(after);
				} catch (Exception e1) {
					logger.error("解析失败： str= \r\n"+jsonString, e1);
				}
			}else {
				logger.error(e);
			}
		}
		if (root != null) {
			for (int i = 0; i < root.size(); i++) {
				JsonNode item = root.get(i);
				if(item != null){
					Map<String, String> map = new HashMap<String, String>();
					Iterator<Entry<String, JsonNode>> it = item.fields();
					while (it.hasNext()) {
						Entry<String, JsonNode> entry = (Entry<String, JsonNode>) it.next();
						map.put(entry.getKey(), entry.getValue().asText());
					}
					data.add(map);
				}
			}
		}
		return data;
	}

	@Override
	protected List<Task> buildNewTask(Page page) {
		List<Map<String, String>> data = page.getData();
		List<Task> newTasks = new ArrayList<Task>();
		
		for (int i = 0; i < data.size(); i++) {
			if (fetchComment) {
				//增加相应的评论的抓取任务
				String commentUrl = data.get(i).get("article_url");//http://www.smzdm.com/p/744743
				String productId = data.get(i).get("article_id");//744743
				SMZDMCommentTask commentTask = new SMZDMCommentTask(this.priority+1, productId, commentUrl+"/p1", this.mongoURI, this.dbName, this.updateRecord);
				newTasks.add(commentTask);
			}
			//抓取图片
//			String picUrl = data.get(i).get("article_pic");
//			newTasks.add(new SMZDMImageTask(picUrl, productId, this.mongoURI, this.dbName));
		}
		
		//增加新的主页抓取任务
		if (data.size() > 0) {
			int pos = data.size()-1;
			String timesort = data.get(pos).get("timesort");
//			String article_date = data.get(pos).get("article_date");
//			if(article_date.length() <= 5){//"article_date":"22:31",只要当日的
//				SMZDMTask task = new SMZDMTask(this.priority, timesort, this.mongoURI, this.dbName);
//				newTasks.add(task);
//			}
			String article_date = data.get(pos).get("article_date_full");
			try {
				if(DateUtil.getDateFromString(article_date).after(DateUtil.getDateFromString(this.stopDate))){//
					SMZDMTask task = new SMZDMTask(this.priority, timesort, this.stopDate, this.mongoURI, this.dbName, this.updateRecord, this.fetchComment);
					newTasks.add(task);
				}
			} catch (ParseException e) {
				logger.error(e);
			}
		}else{
			//如果json解析失败，导致上面的data.size()=0，调整timesort并重新添加任务
			long newTimeSort = Long.parseLong(timesort) - 5*1000;//减少5s
			SMZDMTask task = new SMZDMTask(this.priority, String.valueOf(newTimeSort), this.stopDate, this.mongoURI, this.dbName, this.updateRecord, this.fetchComment);
			newTasks.add(task);
			logger.info("抓取 "+this+" data=0, 调整timesort重试.");
		}
		return newTasks;
	}


	@Override
	public String toString() {
		return "SMZDMTask [timesort=" + timesort + ", stopDate=" + stopDate
				+ ", mongoURI=" + mongoURI + ", dbName=" + dbName
				+ ", updateRecord=" + updateRecord + ", fetchComment="
				+ fetchComment + "]";
	}

	public String getTimesort() {
		return timesort;
	}

	public void setTimesort(String timesort) {
		this.timesort = timesort;
	}

	public String getMongoURI() {
		return mongoURI;
	}

	public void setMongoURI(String mongoURI) {
		this.mongoURI = mongoURI;
	}

	public String getDbName() {
		return dbName;
	}

	public void setDbName(String dbName) {
		this.dbName = dbName;
	}

	public String getStopDate() {
		return stopDate;
	}

	public void setStopDate(String stopDate) {
		this.stopDate = stopDate;
	}
}

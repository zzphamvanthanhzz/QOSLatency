/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package qosbaomoi;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import java.util.concurrent.TimeUnit;
import org.influxdb.InfluxDBFactory;
import org.influxdb.InfluxDB;
import org.influxdb.dto.Point;
import java.util.Map;
import java.util.HashMap;
import org.apache.commons.configuration.PropertiesConfiguration;
import com.google.common.io.Files;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.*;
import org.apache.http.client.config.RequestConfig;

/**
 *
 * @author root
 */
public class QOSBaoMoi {

	class ResponseValue {

		Integer status_code;
		long loadtime; // ms
		long content_length; // bytes
		String conninfo; //IP:port or ConnectionTimeout= ... or ErrorMessage= ...
	}
	private String Client;
	private String USER_AGENT_LIST;
	private String InfluxDBHost;
	private String InfluxDBPort;
	private String InfluxDBUrl;
	private Integer totalImage;
	private String Url;
	private String CompareUrl;
	private String ExUrl;
	private String DBUser;
	private String DBPassword;
	private Integer period;//minutes
	private String DBName;
	private String Extensions;
	private Log log;
	private Integer pingReqs;
	private Integer Threshold;
	private Integer connTimeout;

	private boolean loadConfigure(String path) {
		try {
			PropertiesConfiguration config = new PropertiesConfiguration(path);
			Client = config.getProperty("client") == null ? "127.0.0.1" : config.getProperty("client").toString();
			USER_AGENT_LIST = config.getProperty("user_agent") == null ? "Mozilla/4.0" : config.getProperty("user_agent").toString();
			InfluxDBHost = config.getProperty("host") == null ? "10.30.80.13" : config.getProperty("host").toString();
			InfluxDBPort = config.getProperty("port") == null ? "8086" : config.getProperty("port").toString();
			InfluxDBUrl = config.getProperty("influxdburl") == null ? "" : config.getProperty("influxdburl").toString();
			totalImage = Integer.parseInt(config.getProperty("total_images") == null ? "5" : config.getProperty("total_images").toString());
			DBUser = config.getProperty("user") == null ? "root" : config.getProperty("user").toString();
			DBPassword = config.getProperty("pass") == null ? "root" : config.getProperty("pass").toString();
			Url = config.getProperty("url") == null ? "http://baomoi.com.vn" : config.getProperty("url").toString();
			CompareUrl = config.getProperty("compareUrl") == null ? "http://google.com.vn" : config.getProperty("compareUrl").toString();
			ExUrl = config.getProperty("exurl") == null ? "www.baomoi.com.vn;m.baomoi.com" : config.getProperty("exurl").toString();
			period = Integer.parseInt(config.getProperty("period") == null ? "6" : config.getProperty("period").toString()) * 60 * 1000;
			DBName = config.getProperty("dbname") == null ? "QOSBaoMoi" : config.getProperty("dbname").toString();
			Extensions = config.getProperty("extensions") == null ? "jpg;jpeg" : config.getProperty("extensions").toString();
			pingReqs = Integer.parseInt(config.getProperty("pingreq") == null ? "10" : config.getProperty("pingreq").toString());
			Threshold = Integer.parseInt(config.getProperty("threshold") == null ? "3000" : config.getProperty("threshold").toString());
			connTimeout = Integer.parseInt(config.getProperty("conntimeout") == null ? "15000" : config.getProperty("conntimeout").toString());
			System.out.printf("%s %s %s\n", Client, USER_AGENT_LIST, Extensions);
		} catch (Exception ex) {
//			System.out.printf("Config file not found %s %s\n", path, ex.toString());
			log.error(ex);
			return false;
		}
		return true;
	}

	private String getFileExt(String filename) {
		return Files.getFileExtension(filename);
	}

	private ResponseValue loadUrl(String url, String User_Agent) {
		try {
			//Custom HttpClient with connection timeout = 10s
			RequestConfig reqConfig = RequestConfig.custom()
					//					.setSocketTimeout(5000) //time between 2 consecutive packets
					.setConnectTimeout(connTimeout) //time till connection is established in milliseconds
					//					.setConnectionRequestTimeout(5000) // time till connection request is accepted in connection manager
					.build();
			ResponseValue res;
			try (CloseableHttpClient client = HttpClients.custom()
					.setDefaultRequestConfig(reqConfig)
					.build()) {
				HttpGet httpGet = new HttpGet(url);
				httpGet.addHeader("User-Agent", User_Agent);
				long start = System.currentTimeMillis();
				CloseableHttpResponse response = client.execute(httpGet);
				long end = System.currentTimeMillis();
				long loadtime = end - start;
				res = new ResponseValue();
				if (response.getFirstHeader("Content-Length") != null) {
					res.content_length = Integer.parseInt(response.getFirstHeader("Content-Length").getValue());
				} else {
					res.content_length = 0;
				}	//Response
				res.loadtime = loadtime;
				if (response.getHeaders("conninfo") != null) {
					res.conninfo = response.getHeaders("conninfo")[0].getValue();
				} else {
					res.conninfo = "";
				}
				res.status_code = response.getStatusLine().getStatusCode();
			}
			return res;
		} catch (IOException ex) {
			Logger.getLogger(QOSBaoMoi.class.getName()).log(Level.SEVERE, null, ex);
			return null;
		}
	}

	public void run(String configPath) {
		Map<String, Integer> status_code_map = new HashMap<>();
		log = LogFactory.getLog("LogQOSBaoMoi");
		log.info("App start running");
		if (!loadConfigure(configPath)) {
			return;
		}
		while (true) {
			long timeNow = System.currentTimeMillis();
			String influxDBUrl = "";
			if (InfluxDBUrl.isEmpty()) {
				influxDBUrl = "http://".concat(InfluxDBHost).concat(":").concat(InfluxDBPort);
				System.out.printf("Connect InfluxDB directly : %s:%s\n", InfluxDBHost, InfluxDBPort);
			} else {
				influxDBUrl = InfluxDBUrl;
				System.out.printf("Connect InfluxDB via Haproxy : %s\n", InfluxDBUrl);
			}
			InfluxDB influxDB = InfluxDBFactory.connect(influxDBUrl, DBUser, DBPassword);

			//Get static of link
			for (String USER_AGENT : USER_AGENT_LIST.split(";")) {
				log.info(String.format("Submit stats at %d, %s", timeNow / 1000, USER_AGENT));

//Latency of specific URL
//split exurl by ; to get exurl for loadtime
				String[] urlList = ExUrl.split(";");
				for (String url_ : urlList) {
					ResponseValue res = loadUrl("http://".concat(url_), USER_AGENT);
					if (res != null) {
						Boolean netfail = false;
						if (res.loadtime > Threshold) {
							long cpLoad = compareLoad(CompareUrl, USER_AGENT);
							netfail = cpLoad <= Threshold;
							if (netfail) {
								log.info(String.format("Ping result %s %d VS %s %d", CompareUrl, cpLoad, url_, res.loadtime));
							}
						}
						Point wwwPoint = Point.measurement("loadtime")
								.time(timeNow, TimeUnit.MILLISECONDS)
								.field("load", res.loadtime)
								.tag("client", Client)
								.tag("netfail", netfail ? "true" : "false")
								.tag("img", url_)
								.tag("status_code", res.status_code.toString())
								.tag("User-Agent", USER_AGENT)
								.build();
						try {
							influxDB.write(DBName, "default", wwwPoint);
						} catch (Exception ex) {
							log.error(ex.toString());
						}
					}
				}

				try {
					//get total image link
					Document doc = Jsoup.connect(Url).get();
					Elements img = doc.select("img[src]");
					Elements js = doc.select("script[src]");
					Elements css = doc.select("link[href]").select("[type=text/css");
//IMAGE					
					Integer count = 0;
					float average = 0;
					status_code_map.put("1xx", 0);
					status_code_map.put("2xx", 0);
					status_code_map.put("3xx", 0);
					status_code_map.put("4xx", 0);
					status_code_map.put("5xx", 0);
					for (Element elem : img) {
						//submit average load time + status code if last
						if (count == totalImage.intValue()) {
							Boolean netfail = false;
							if (average / totalImage > Threshold) {
								long cpLoad = compareLoad(CompareUrl, USER_AGENT);
								netfail = cpLoad <= Threshold;
								if (netfail) {
									log.info(String.format("Ping result %s %d VS AverageIMG %f", CompareUrl, cpLoad, average / totalImage));
								}
							}
							Point pointAverage = Point.measurement("loadtime")
									.time(timeNow, TimeUnit.MILLISECONDS)
									.field("load", Math.round(average / totalImage))
									.tag("client", Client)
									.tag("img", "Average")
									.tag("netfail", netfail ? "true" : "false")
									.tag("User-Agent", USER_AGENT)
									.build();
							try {
								influxDB.write(DBName, "default", pointAverage);
							} catch (Exception ex) {
								log.error(ex.toString());
							}
							for (Map.Entry<String, Integer> entry : status_code_map.entrySet()) {
								Point point2 = Point.measurement("statuscode")
										.time(timeNow, TimeUnit.MILLISECONDS)
										.field("total", entry.getValue())
										.tag("client", Client)
										.tag("status_code", entry.getKey())
										.tag("User-Agent", USER_AGENT)
										.build();
								try {
									influxDB.write(DBName, "default", point2);
								} catch (Exception ex) {
									log.error(ex.toString());
								}
							}
							break;
						} else {
							String img_url = elem.attr("src");
							boolean netfail = false;
							//check if img_url is ended with common image extensions: jpg, jpeg, 
							String ext = getFileExt(img_url);
							if (Extensions.contains(ext)) {
								ResponseValue response = loadUrl(img_url, USER_AGENT);
								if (response == null) {
									continue;
								}
								String conninfo = response.conninfo;
								String ip = conninfo.split("/")[1];
//								System.out.printf("IMG=========%s\n", response.getHeaders("conninfo")[0].getValue());
								long loadtime = response.loadtime;
								Integer status_code = response.status_code;
								if (status_code >= 200 && status_code < 400) {
									average += loadtime;
								}
								//Check netfailed. for each image
								if (loadtime > Threshold) {
									long cpLoad = compareLoad(CompareUrl, USER_AGENT);
									netfail = cpLoad <= Threshold;
									if (netfail) {
										log.info(String.format("Ping result %s %d VS %s %d", CompareUrl, cpLoad, img_url, loadtime));
									}
								}
								//insert influxdb
								Point point = Point.measurement("loadtime")
										.time(timeNow, TimeUnit.MILLISECONDS)
										.field("load", loadtime) // bytes/ms (0 if status_code not 2xx)
										.tag("img", img_url.concat(",").concat(ip))
										.tag("status_code", status_code.toString())
										.tag("netfail", netfail ? "true" : "false")
										.tag("client", Client)
										.tag("User-Agent", USER_AGENT)
										.build();
								try {
									influxDB.write(DBName, "default", point);
								} catch (Exception ex) {
									log.error(ex.toString());
								}
								if (status_code >= 100 && status_code < 200) {
									log.error(Integer.toString(status_code).concat(" - ").concat(img_url));
									status_code_map.put("1xx", status_code_map.get("1xx") + 1);
								} else if (status_code >= 200 && status_code < 300) {
									log.info(Integer.toString(status_code).concat(" - ").concat(img_url));
									status_code_map.put("2xx", status_code_map.get("2xx") + 1);
								} else if (status_code >= 300 && status_code < 400) {
									log.error(Integer.toString(status_code).concat(" - ").concat(img_url));
									status_code_map.put("3xx", status_code_map.get("3xx") + 1);
								} else if (status_code >= 400 && status_code < 500) {
									log.error(Integer.toString(status_code).concat(" - ").concat(img_url));
									status_code_map.put("4xx", status_code_map.get("4xx") + 1);
								} else {
									log.error(Integer.toString(status_code).concat(" - ").concat(img_url));
									status_code_map.put("5xx", status_code_map.get("5xx") + 1);
								}
								//notify here
								//increase total 
								count++;
							}
						}
					}
					//if total loaded less than totalImage config

					if (count <= totalImage) {
						Boolean netfail = false;
						if (average / count > Threshold) {
							long cpLoad = compareLoad(CompareUrl, USER_AGENT);
							netfail = cpLoad <= Threshold;
							if (netfail) {
								log.info(String.format("Ping result %s %d VS AverageIMG %f", CompareUrl, cpLoad, average / count));
							}
						}
						Point pointAverage = Point.measurement("loadtime")
								.time(timeNow, TimeUnit.MILLISECONDS)
								.field("load", Math.round(average / count))
								.tag("client", Client)
								.tag("img", "Average")
								.tag("netfail", netfail ? "true" : "false")
								.tag("User-Agent", USER_AGENT)
								.build();
						try {
							influxDB.write(DBName, "default", pointAverage);
						} catch (Exception ex) {
							log.error(ex.toString());
						}
					}
//End IMAGE
//JS files
					count = 0;
					average = 0;
					status_code_map.put("1xx", 0);
					status_code_map.put("2xx", 0);
					status_code_map.put("3xx", 0);
					status_code_map.put("4xx", 0);
					status_code_map.put("5xx", 0);
					for (Element elem : js) {
						//submit average load time + status code if last
						if (count == totalImage.intValue()) {
							Boolean netfail = false;
							if (average / totalImage > Threshold) {
								long cpLoad = compareLoad(CompareUrl, USER_AGENT);
								netfail = cpLoad <= Threshold;
								if (netfail) {
									log.info(String.format("Ping result %s %d VS AverageJS %f", CompareUrl, cpLoad, average / totalImage));
								}
							}
							Point pointAverage = Point.measurement("loadtime")
									.time(timeNow, TimeUnit.MILLISECONDS)
									.field("load", Math.round(average / totalImage))
									.tag("client", Client)
									.tag("js", "Average")
									.tag("netfail", netfail ? "true" : "false")
									.tag("User-Agent", USER_AGENT)
									.build();
							try {
								influxDB.write(DBName, "default", pointAverage);
							} catch (Exception ex) {
								log.error(ex.toString());
							}
							for (Map.Entry<String, Integer> entry : status_code_map.entrySet()) {
								Point point2 = Point.measurement("statuscode")
										.time(timeNow, TimeUnit.MILLISECONDS)
										.field("total", entry.getValue())
										.tag("client", Client)
										.tag("status_code", entry.getKey())
										.tag("User-Agent", USER_AGENT)
										.build();
								try {
									influxDB.write(DBName, "default", point2);
								} catch (Exception ex) {
									log.error(ex.toString());
								}
							}
							break;
						} else {

							String js_url = elem.attr("src");
							//check if img_url is ended with common image extensions: jpg, jpeg, 
//							System.out.println(js_url);
							Boolean ret = js_url.startsWith("http");
							boolean netfail = false;
							if (!ret) {
								js_url = "http:".concat(js_url);
							}
							String ext = getFileExt(js_url);
							if (Extensions.contains(ext)) {
								ResponseValue response = loadUrl(js_url, USER_AGENT);
								if (response == null) {
									continue;
								}
								String conninfo = response.conninfo;
								String ip = conninfo.split("/")[1];
//								System.out.printf("IMG=========%s\n", response.getHeaders("conninfo")[0].getValue());
								long loadtime = response.loadtime;
								Integer status_code = response.status_code;
								if (status_code >= 200 && status_code < 400) {
									average += loadtime;
								}
								//Check netfailed. for each js
								if (loadtime > Threshold) {
									long cpLoad = compareLoad(CompareUrl, USER_AGENT);
									netfail = cpLoad <= Threshold;
									if (netfail) {
										log.info(String.format("Ping result %s %d VS js_url %s %d", CompareUrl, cpLoad, js_url, loadtime));
									}
								}
								//insert influxdb
								Point point = Point.measurement("loadtime")
										.time(timeNow, TimeUnit.MILLISECONDS)
										.field("load", loadtime) // bytes/ms (0 if status_code not 2xx)
										.tag("js", js_url.concat(",").concat(ip))
										.tag("status_code", status_code.toString())
										.tag("netfail", netfail ? "true" : "false")
										.tag("client", Client)
										.tag("User-Agent", USER_AGENT)
										.build();
								try {
									influxDB.write(DBName, "default", point);
								} catch (Exception ex) {
									log.error(ex.toString());
								}
								if (status_code >= 100 && status_code < 200) {
									log.error(Integer.toString(status_code).concat(" - ").concat(js_url));
									status_code_map.put("1xx", status_code_map.get("1xx") + 1);
								} else if (status_code >= 200 && status_code < 300) {
									log.info(Integer.toString(status_code).concat(" - ").concat(js_url));
									status_code_map.put("2xx", status_code_map.get("2xx") + 1);
								} else if (status_code >= 300 && status_code < 400) {
									log.error(Integer.toString(status_code).concat(" - ").concat(js_url));
									status_code_map.put("3xx", status_code_map.get("3xx") + 1);
								} else if (status_code >= 400 && status_code < 500) {
									log.error(Integer.toString(status_code).concat(" - ").concat(js_url));
									status_code_map.put("4xx", status_code_map.get("4xx") + 1);
								} else {
									log.error(Integer.toString(status_code).concat(" - ").concat(js_url));
									status_code_map.put("5xx", status_code_map.get("5xx") + 1);
								}
								//notify here
								//increase total 
								count++;
							}
						}
					}
					//if total loaded less than totalImage config
					if (count <= totalImage) {
						Boolean netfail = false;
						if (average / count > Threshold) {
							long cpLoad = compareLoad(CompareUrl, USER_AGENT);
							netfail = cpLoad <= Threshold;
							if (netfail) {
								log.info(String.format("Ping result %s %d VS AverageJS %f", CompareUrl, cpLoad, average / count));
							}
						}
						Point pointAverage = Point.measurement("loadtime")
								.time(timeNow, TimeUnit.MILLISECONDS)
								.field("load", Math.round(average / count))
								.tag("client", Client)
								.tag("netfail", netfail ? "true" : "false")
								.tag("js", "Average")
								.tag("User-Agent", USER_AGENT)
								.build();
						try {
							influxDB.write(DBName, "default", pointAverage);
						} catch (Exception ex) {
							log.error(ex.toString());
						}
					}
//END JS					
//CSS files
					count = 0;
					average = 0;
					status_code_map.put("1xx", 0);
					status_code_map.put("2xx", 0);
					status_code_map.put("3xx", 0);
					status_code_map.put("4xx", 0);
					status_code_map.put("5xx", 0);
					for (Element elem : css) {
						//submit average load time + status code if last
						if (totalImage.intValue() == count) {
							Boolean netfail = false;
							if (average / totalImage > Threshold) {
								long cpLoad = compareLoad(CompareUrl, USER_AGENT);
								netfail = cpLoad <= Threshold;
								if (netfail) {
									log.info(String.format("Ping result %s %d VS Average CSS %f", CompareUrl, cpLoad, average / totalImage));
								}
							}
							Point pointAverage = Point.measurement("loadtime")
									.time(timeNow, TimeUnit.MILLISECONDS)
									.field("load", Math.round(average / totalImage))
									.tag("client", Client)
									.tag("netfail", netfail ? "true" : "false")
									.tag("css", "Average")
									.tag("User-Agent", USER_AGENT)
									.build();
							try {
								influxDB.write(DBName, "default", pointAverage);
							} catch (Exception ex) {
								log.error(ex.toString());
							}
							for (Map.Entry<String, Integer> entry : status_code_map.entrySet()) {
								Point point2 = Point.measurement("statuscode")
										.time(timeNow, TimeUnit.MILLISECONDS)
										.field("total", entry.getValue())
										.tag("client", Client)
										.tag("status_code", entry.getKey())
										.tag("User-Agent", USER_AGENT)
										.build();
								try {
									influxDB.write(DBName, "default", point2);
								} catch (Exception ex) {
									log.error(ex.toString());
								}
							}
							break;
						} else {
							String css_url = elem.attr("href");
							//check if img_url is ended with common image extensions: jpg, jpeg, 
							Boolean ret = css_url.startsWith("http:");
							boolean netfail = false;
							if (!ret) {
								css_url = "http:".concat(css_url);
							}
							String ext = getFileExt(css_url);
							if (Extensions.contains(ext)) {
								ResponseValue response = loadUrl(css_url, USER_AGENT);
								if (response == null) {
									continue;
								}
								String conninfo = response.conninfo;
								String ip = conninfo.split("/")[1];
//								System.out.printf("IMG=========%s\n", response.getHeaders("conninfo")[0].getValue());
								long loadtime = response.loadtime;
								Integer status_code = response.status_code;
								if (status_code >= 200 && status_code < 400) {
									average += loadtime;
								}
								//Check netfailed. for each image
								if (loadtime > Threshold) {
									long cpLoad = compareLoad(CompareUrl, USER_AGENT);
									netfail = cpLoad <= Threshold;
									if (netfail) {
										log.info(String.format("Ping result %s %d VS css_url %s %d", CompareUrl, cpLoad, css_url, loadtime));
									}
								}
								//insert influxdb
								Point point = Point.measurement("loadtime")
										.time(timeNow, TimeUnit.MILLISECONDS)
										.field("load", loadtime) // bytes/ms (0 if status_code not 2xx)
										.tag("css", css_url.concat(",").concat(ip))
										.tag("status_code", status_code.toString())
										.tag("netfail", netfail ? "true" : "false")
										.tag("client", Client)
										.tag("User-Agent", USER_AGENT)
										.build();
								try {
									influxDB.write(DBName, "default", point);
								} catch (Exception ex) {
									log.error(ex.toString());
								}
								if (status_code >= 100 && status_code < 200) {
									log.error(Integer.toString(status_code).concat(" - ").concat(css_url));
									status_code_map.put("1xx", status_code_map.get("1xx") + 1);
								} else if (status_code >= 200 && status_code < 300) {
									log.info(Integer.toString(status_code).concat(" - ").concat(css_url));
									status_code_map.put("2xx", status_code_map.get("2xx") + 1);
								} else if (status_code >= 300 && status_code < 400) {
									log.error(Integer.toString(status_code).concat(" - ").concat(css_url));
									status_code_map.put("3xx", status_code_map.get("3xx") + 1);
								} else if (status_code >= 400 && status_code < 500) {
									log.error(Integer.toString(status_code).concat(" - ").concat(css_url));
									status_code_map.put("4xx", status_code_map.get("4xx") + 1);
								} else {
									log.error(Integer.toString(status_code).concat(" - ").concat(css_url));
									status_code_map.put("5xx", status_code_map.get("5xx") + 1);
								}
								//notify here
								//increase total 
								count++;
							}
						}
					}
					//if total loaded less than totalImage config
					if (count <= totalImage) {
						Boolean netfail = false;
						if (average / count > Threshold) {
							long cpLoad = compareLoad(CompareUrl, USER_AGENT);
							netfail = cpLoad <= Threshold;
							if (netfail) {
								log.info(String.format("Ping result %s %d VS AverageCSS", CompareUrl, cpLoad, average / count));
							}
						}
						Point pointAverage = Point.measurement("loadtime")
								.time(timeNow, TimeUnit.MILLISECONDS)
								.field("load", Math.round(average / count))
								.tag("client", Client)
								.tag("netfail", netfail ? "true" : "false")
								.tag("css", "Average")
								.tag("User-Agent", USER_AGENT)
								.build();
						try {
							influxDB.write(DBName, "default", pointAverage);
						} catch (Exception ex) {
							log.error(ex.toString());
						}
					}
//END CSS					
					try {
						Thread.sleep(period);                 //1000 milliseconds is one second.
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						log.error(ex);
					}
				} catch (IOException ex) {
					log.error(ex);
				} catch (IllegalArgumentException ex) {
					Logger.getLogger(QOSBaoMoi.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
		}
	}

	public long compareLoad(String url_, String USER_AGENT) {
		try {
			long loadtime;
			//Custom HttpClient with connection timeout = 10s
			RequestConfig reqConfig = RequestConfig.custom()
					//					.setSocketTimeout(5000) //time between 2 consecutive packets
					.setConnectTimeout(connTimeout) //time till connection is established in milliseconds
					//					.setConnectionRequestTimeout(5000) // time till connection request is accepted in connection manager
					.build();
			CloseableHttpClient client = HttpClients.custom()
					.setDefaultRequestConfig(reqConfig)
					.build();

			HttpGet httpGet = new HttpGet(url_);
			httpGet.addHeader("User-Agent", USER_AGENT);

			long start = System.currentTimeMillis();
			CloseableHttpResponse response = client.execute(httpGet);
			long end = System.currentTimeMillis();
			loadtime = end - start;
			return loadtime;
		} catch (IOException ex) {
			Logger.getLogger(QOSBaoMoi.class.getName()).log(Level.SEVERE, null, ex);
			return 5000; // means loadtime error for compare url
		}
	}

	/**
	 * This pingTest only run in window
	 *
	 * @param url_
	 * @param pingreq_
	 * @return
	 */
	public Boolean pingTest(String url_, Integer pingreq_) {
		String cmd = "ping -n " + pingreq_ + " " + url_;
		try {
			Runtime r = Runtime.getRuntime();
			Process p = r.exec(cmd);
			try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String inputline;
				while ((inputline = in.readLine()) != null) {
					if (inputline.contains("Lost")) {
						log.info(String.format("Ping %s result %s %d", url_, inputline, inputline.contains("Lost = 0") ? 0 : 1));
//						System.out.printf("Ping %s result %s %d", url_, inputline, inputline.contains("Lost = 0") ? 0 : 1);
						return inputline.contains("Lost = 0");
					}
				}
			} catch (Exception exBuffer) {
				log.error(exBuffer.toString());
				return true;
			}
		} catch (Exception ex) {
			log.error(ex.toString());
			return true;
		}
		return true;
	}
}

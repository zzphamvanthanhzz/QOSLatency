/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package qosbaomoi;

import java.util.Properties;

/**
 *
 * @author root
 */
public class main {

	public static void main(String[] args) {
		qosbaomoi.QOSBaoMoi qos = new QOSBaoMoi();
		Properties props = System.getProperties();
		String configfile = props.getProperty("config") == null ? "" : props.getProperty("config");
		System.out.printf("Load config file: %s\n", configfile);
//		qos.run(configfile);
		qos.run("/home/thanhpv/Workspace/Code/gitrepo/TestQOSBaoMoi/config.properties");
//		qos.pingTest("m.news.zing.vn", 10);
//		qos.compareLoad("http://google.com.vn", "Mozillar/4.0");
	}
}

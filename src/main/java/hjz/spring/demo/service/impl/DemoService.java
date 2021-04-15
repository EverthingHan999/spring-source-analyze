package hjz.spring.demo.service.impl;


import hjz.spring.demo.annotation.GPService;
import hjz.spring.demo.service.IDemoService;

/**
 * 核心业务逻辑
 */
@GPService
public class DemoService implements IDemoService {

	public String get(String name) {
		return "My name is " + name + ",from service.";
	}

}

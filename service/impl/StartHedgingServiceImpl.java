package com.yin.service.impl;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import com.yin.service.*;
import com.yin.util.AtmHelp;
import org.apache.activemq.command.ActiveMQDestination;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.okex.websocket.OkexConstant;

/**
 * @author yin
 * @createDate 2018年12月27日 上午9:46:53
 */
@Service("startHedgingService")
public class StartHedgingServiceImpl implements WebSocketService {
	@Resource(name = "instrumentsDepthService")
	InstrumentsDepthService instrumentsDepthService;
	@Resource(name = "instrumentsTickersService")
	InstrumentsTickersService instrumentsTickersService;
	@Resource(name = "instrumentService")
	InstrumentService instrumentService;
	@Autowired
	TradeApiService tradeApiService;
	@Resource(name = "futureAccountService")
	private FutureAccountService futureAccountService;
	@Autowired
	CoinServiceImpl coinService;
	HedgingConfigManager hedgingConfigManager = HedgingConfigManager.getInstance();
	HedgingClient hedgingClient;

	private Map<String, Float> lastAtmIn = new ConcurrentHashMap<String, Float>();
	private boolean isOpening = false;

	@PostConstruct
	public void init() {
		hedgingClient = new HedgingClient(tradeApiService);
	}

	private void execute(String table, String instrumentId) {
		hedgingClient.start();
		Instrument instrument = instrumentService.getInstrument(table, instrumentId);
		if (instrument != null) {
			String coin = instrument.getCoin();
			Instrument thisInstrument = instrumentService.getInstrument(OkexConstant.FUTURES_DEPTH, coin, "this_week");
			// 已过期
			if (thisInstrument != null && thisInstrument.getDeliveryTime() < System.currentTimeMillis()) {
				thisInstrument = null;
			}
			Instrument nextInstrument = instrumentService.getInstrument(OkexConstant.FUTURES_DEPTH, coin, "next_week");
			// 已过期
			if (nextInstrument != null && nextInstrument.getDeliveryTime() < System.currentTimeMillis()) {
				nextInstrument = null;
			}
			Instrument quarterInstrument = instrumentService.getInstrument(OkexConstant.FUTURES_DEPTH, coin, "quarter");
			// 已过期
			if (quarterInstrument != null && quarterInstrument.getDeliveryTime() < System.currentTimeMillis()) {
				quarterInstrument = null;
			}
			Instrument spotInstrument = instrumentService.getInstrument(OkexConstant.SPOT_DEPTH, coin, "USDT");
			switch (instrument.getContractType()) {
			case "this":
				// spot,this_week
				//execute(hedgingConfigManager.getConfigs(coin, "tt"), spotInstrument, thisInstrument);
				break;
			case "this_week":
				// spot,this_week
				//execute(hedgingConfigManager.getConfigs(coin, "tt"), spotInstrument, thisInstrument);
				// this_week,next_week
				//execute(hedgingConfigManager.getConfigs(coin, "tn"), thisInstrument, nextInstrument);
				// this_week,quarter
				execute(hedgingConfigManager.getConfigs(coin, "tq"), thisInstrument, quarterInstrument);
				break;
			case "next_week":
				//execute(hedgingConfigManager.getConfigs(coin, "tn"), thisInstrument, nextInstrument);
				// next_week,quarter
				//execute(hedgingConfigManager.getConfigs(coin, "nq"), nextInstrument, quarterInstrument);
				break;
			case "quarter":
				execute(hedgingConfigManager.getConfigs(coin, "tq"), thisInstrument, quarterInstrument);
				//execute(hedgingConfigManager.getConfigs(coin, "nq"), nextInstrument, quarterInstrument);
				break;
			default:
				;
			}
		}
		try {
			hedgingClient.finish();
		} catch (TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void execute(List<HedgingConfig> configs, Instrument thisInstrument, Instrument nextInstrument) {
		if (configs != null && thisInstrument != null && nextInstrument != null
				&& !thisInstrument.getInstrumentId().equals(nextInstrument.getInstrumentId())
				&& thisInstrument.getDeliveryTime() != nextInstrument.getDeliveryTime()) {
			// 从大到小排列，优先提交高指数的策略
			/**
			configs.sort(new Comparator<HedgingConfig>() {
				@Override
				public int compare(HedgingConfig o1, HedgingConfig o2) {
					// TODO Auto-generated method stub
					return Float.compare(o2.getAtmInRate(), o1.getAtmInRate());
				}
			});
			**/

			for (HedgingConfig config : configs) {
//				手动近卖远买
				execute(config, thisInstrument, nextInstrument, config.getAtmInRate());
			}


		}
	}

	private void execute(HedgingConfig config, Instrument preInstrument, Instrument lastInstrument,
			float thresholdRate) {
		if (config.isStart() && VolumeManager.getInstance().getVolume(config) > 0) {
			if (!isInHegingHour(config, preInstrument, lastInstrument)) {
				return;
			}
			Level2Bean level2Buy = null;
			Level2Bean level2Sell = null;
			if(config.getTypeAction() == 0) { //当周做多，当季做空
				level2Buy = instrumentsDepthService.getBuyLevel2Postion(preInstrument.getInstrumentId(),
						config.getBuyLevel());
				level2Sell = instrumentsDepthService.getSellLevel2Postion(lastInstrument.getInstrumentId(),
						config.getSellLevel());
			}else{  //当周做空，当季做多
				level2Buy = instrumentsDepthService.getBuyLevel2Postion(lastInstrument.getInstrumentId(),
						config.getBuyLevel());
				level2Sell = instrumentsDepthService.getSellLevel2Postion(preInstrument.getInstrumentId(),
						config.getSellLevel());
			}

			TickerBean tickerBean1 = instrumentsTickersService.getLastPrice(preInstrument.getInstrumentId());
			TickerBean tickerBean2 = instrumentsTickersService.getLastPrice(lastInstrument.getInstrumentId());
			TickerBean thisTickerIndex = instrumentsTickersService.getFiveMinIndexPrice(preInstrument.getInstrumentId());
			TickerBean nextTickerIndex = instrumentsTickersService.getFiveMinIndexPrice(lastInstrument.getInstrumentId());
			System.out.println("买：" + level2Buy.getFloatPrice() + "Volum: " + level2Buy.getDoubleVolume());
			System.out.println("卖：" + level2Sell.getFloatPrice() + "Volum: " + level2Sell.getDoubleVolume());

			if (openHedging(config, tickerBean1, tickerBean2, thisTickerIndex,nextTickerIndex)) {
				System.out.println("进场啦");
				float dangzhou = tickerBean1.getFloatLastPrice();
				float dangji = tickerBean2.getFloatLastPrice();
				float dangzhou_index = thisTickerIndex.getFloatIndexPrice();
				float dangji_index = nextTickerIndex.getFloatIndexPrice();
				float atm_index = AtmHelp.computerAtm(dangji,dangji_index,dangzhou,dangzhou_index);

				int volume = getHedgingVolumns(level2Buy, level2Sell, config, config.getStartPremiumRate());
				while (volume >= 10) {
					Hedging hedging = null;
					hedging = hedgingTrade(level2Buy, level2Sell, 10, 10, "1", config, config.getStartPremiumRate(),atm_index);
					hedging.setAmount(volume);
					if (hedging != null) {
						addHedging(hedging);
					}
					volume = volume - 10;
				}
				if(volume > 0){
					Hedging hedging = null;
					hedging = hedgingTrade(level2Buy, level2Sell, volume, volume, "1", config, config.getStartPremiumRate(),atm_index);
					hedging.setAmount(volume);
					if (hedging != null) {
						addHedging(hedging);
					}
				}
			}else{
			}
		}
	}

	private void addHedging(Hedging hedging) {
		hedgingClient.addHedging(hedging);
		HedgingManager.getInstance().addHedging(hedging);
	}

	private float getAvailablePrice(Level2Bean level2Buy, Level2Bean level2Sell, float premiumRate) {
		return level2Sell.getFloatPrice() * (1 - premiumRate) / 2f
				+ level2Buy.getFloatPrice() * (1 + premiumRate) / 2f;
	}

	/**
	 * 提供对冲开仓平仓服务
	 *
	 * @param level2Buy   当前市场委托买价
	 * @param level2Sell  当前市场委托卖价
	 * @param config      对冲策略配置
	 * @param premiumRate 溢价开平仓率
	 * @return
	 */
	private Hedging hedgingTrade(Level2Bean level2Buy, Level2Bean level2Sell, int buyVolume, int sellVolume,
			String type, HedgingConfig config, float premiumRate,float atm_index) {
		HedgingTrade buyTrade = new HedgingTrade();
		if (buyVolume > 0 && level2Buy != null) {
			buyTrade.setLeverRate(config.getLeverRate());
			buyTrade.setInstrumentId(level2Buy.getInstrumentId());
			Instrument futureInstrument = instrumentService.getInstrument(buyTrade.getInstrumentId());
			buyTrade.setDeliveryTime(futureInstrument.getDeliveryTime());
			buyTrade.setPrice(level2Buy.getFloatPrice() * (1 + premiumRate / 100f));
			buyTrade.setAmount(buyVolume);
			buyTrade.setAtm_index(atm_index);
			if ("3".equals(type)) {
				buyTrade.setType("4");
			} else {
				buyTrade.setType(type);
			}

		}
		HedgingTrade sellTrade = new HedgingTrade();
		if (sellVolume > 0 && level2Sell != null) {
			sellTrade.setLeverRate(config.getLeverRate());
			sellTrade.setInstrumentId(level2Sell.getInstrumentId());
			Instrument futureInstrument = instrumentService.getInstrument(sellTrade.getInstrumentId());
			sellTrade.setDeliveryTime(futureInstrument.getDeliveryTime());
			sellTrade.setPrice(level2Sell.getFloatPrice() * (1 - premiumRate / 100f));
			sellTrade.setAmount(sellVolume);
			sellTrade.setAtm_index(atm_index);
			if ("1".equals(type)) {
				sellTrade.setType("2");
			} else {
				sellTrade.setType(type);
			}
		}
		Hedging hedging = new Hedging(config);
		hedging.setBuyTrade(buyTrade);
		hedging.setSellTrade(sellTrade);
		return hedging;
	}

	private int getHedgingVolumns(Level2Bean level2Buy, Level2Bean level2Sell, HedgingConfig config, float premiumRate){
		Hedging hedging = null;
		// 计算可以交易合约张数，必须是买卖挂单最小值，同时小于最大单次下单合约数，小于可交易合约数
		/*
		int volume = Math.min(level2Buy.getIntVolume(), level2Sell.getIntVolume()) - hedgingClient.getUsedVolume();
		System.out.println("买单最小值：" + level2Buy.getIntVolume());
		System.out.println("卖单最小值：" + level2Sell.getIntVolume());
		System.out.println("已用：" + hedgingClient.getUsedVolume());
		// 对冲后委托价上必须剩余这么多合约张数，防止对冲失败
		//int levelVolume = (int) (config.getStartThresholdAmount() / coinService.getUnitAmount(config.getCoin()));
		//volume = volume - levelVolume;
		*/
		// 检查保证金是否足够
		float price = getAvailablePrice(level2Buy, level2Sell, premiumRate);
		System.out.println("拟需要的总金额为：" + price);
		double availableMargin = futureAccountService.getAvailableMargin(config.getCoin());
		System.out.println("availableMargin " + availableMargin+"  getUsedMargin  "+hedgingClient.getUsedMargin());
		availableMargin=availableMargin- hedgingClient.getUsedMargin();// 可用保证金
		int availableVolume = futureAccountService.getAvailableVolume(config.getCoin(), availableMargin, price,
				config.getLeverRate());
		System.out.println("availableVolume " + availableVolume);
		int volume = availableVolume / 2;

		if (config.getMaxTradeVolume() > 0) {
			volume = Math.min(config.getMaxTradeVolume(), volume);
		}

		// 检查库存
		// 限制合约张数，0为不限制
		/*
		if (config.getVolume() > 0) {
			int leftVolume = VolumeManager.getInstance().getVolume(config);
			int AvailableMinusUsed = availableVolume/2 - (config.getVolume() - leftVolume);
			if (leftVolume > 0) {
				volume = Math.min(leftVolume, volume);
				volume = Math.min(AvailableMinusUsed, volume);
			}
			// 减掉库存
			volume = VolumeManager.getInstance().getSetVolume(config, volume);
		}
		*/
		System.out.println("can hedging volume " + volume);
		return volume;
	}

	private boolean isIndexUpdate(int second){
		Calendar cal2 = Calendar.getInstance();
		long currentTime = cal2.getTime().getTime();
		//处理5分钟基准价格
		long fivemin = 10 * 60 *1000;
		if((currentTime%fivemin) <= second*1000 ){
			return false;
		}
		return true;
	}

	/**
	 * 判断是否符合
	 * 开仓条件
	 * 
	 * @param config
	 * @return
	 */
	private boolean openHedging(HedgingConfig config, TickerBean xianjia1, TickerBean xianjia2,
								TickerBean jizhunjia1,TickerBean jizhunjia2) {
		//增加基准价更新前后30秒不能交易
		if(!isIndexUpdate(15)){
			System.out.println("基准价更新15秒之内，不交易");
			return false;
		}
		//1.判断参数是否初始化
		if(xianjia1 == null || xianjia2 == null || jizhunjia1 == null || jizhunjia2 == null){
			return false;
		}
		float atm_index_config = config.getAtmInRate();
		float djz_diff_config = config.getDangjizhouDiffRate();
		float dangzhou = xianjia1.getFloatLastPrice();
		float dangji = xianjia2.getFloatLastPrice();
		float dangzhou_index = jizhunjia1.getFloatIndexPrice();
		float dangji_index = jizhunjia2.getFloatIndexPrice();
		//2.判断参数是否符合基础的数学条件
		if((dangji - dangji_index) == 0f || (dangzhou - dangji_index) == 0f || dangji_index == 0f || dangzhou_index == 0f){
			return false;
		}

		float atm_index = AtmHelp.computerAtm(dangji,dangji_index,dangzhou,dangzhou_index);
		//3. 初始化上一次奥特曼指数
		if(lastAtmIn.get("lastatm") == null){
			lastAtmIn.put("lastatm",atm_index);
			return false;
		}

		float dangji_f = (dangji - dangji_index) / dangji_index;
		float dangzhou_f = (dangzhou - dangzhou_index) / dangzhou_index;
		float lastatm = lastAtmIn.get("lastatm");
		System.out.println("上一次奥特曼指数为:" + lastatm);
		System.out.println("本次奥特曼指数为:" + atm_index);
		if(config.getAtmInSign() != 1) {
			if (config.isStart() && atm_index >= atm_index_config && Math.abs(dangji_f - dangzhou_f) > djz_diff_config) {
				if(lastatm - atm_index >= config.getAtmDiff()) { //下降
					lastAtmIn.put("lastatm",atm_index);
					return true;
				}
			}
		}else{
			if (config.isStart() && atm_index <= atm_index_config && Math.abs(dangji_f - dangzhou_f) > djz_diff_config) {
				if(atm_index - lastatm >= config.getAtmDiff()) { //上升
					lastAtmIn.put("lastatm",atm_index);
					isOpening = true;
					return true;
				}
			}
		}
		long twosec = 2 *1000;
		Calendar cal2 = Calendar.getInstance();
		long currentTime = cal2.getTime().getTime();
		if(currentTime%twosec < 1000) {
			lastAtmIn.put("lastatm", atm_index);
		}
		return false;
	}

	/**
	 * 是否在允许的时间范围内开仓，距离交割日前多少小时可以开仓，交割的时候，16时12分前不进行任何对冲套利
	 * 
	 * @param config
	 * @return
	 */
	private boolean isInHegingHour(HedgingConfig config, Instrument preInstrument,
			Instrument lastInstrument) {
		LocalDateTime localDateTime = LocalDateTime.now();
		// 交割的时候，16时12分前不进行任何对冲套利
		if (localDateTime.getDayOfWeek() == DayOfWeek.FRIDAY) {
			int hour = localDateTime.getHour();
			int minute = localDateTime.getMinute();
			if (hour == 16 && minute < 12) {
				return false;
			}
		}
		// 开仓时间限制
		if (config.getLastHegingHour() == 0) {
			return true;
		}
		long time = System.currentTimeMillis() + config.getLastHegingHour() * 3600000;
		return time < preInstrument.getDeliveryTime() && time < lastInstrument.getDeliveryTime();
	}

	@Override
	public void onReceive(Object obj) {
		if (obj instanceof JSONObject) {
			JSONObject root = (JSONObject) obj;
			if (root.containsKey(OkexConstant.TABLE)) {
				String table = root.getString(OkexConstant.TABLE);
				if (OkexConstant.FUTURES_DEPTH.equals(table) || OkexConstant.FUTURES_DEPTH5.equals(table)) {
					// String action = root.getString("action");
					if (root.containsKey(OkexConstant.DATA)) {
						JSONArray data = root.getJSONArray(OkexConstant.DATA);
						Iterator it = data.iterator();
						while (it.hasNext()) {
							Object instrument = it.next();
							if (instrument instanceof JSONObject) {
								JSONObject instrumentJSON = (JSONObject) instrument;
								String instrumentId = instrumentJSON.getString(OkexConstant.INSTRUMENT_ID);
								execute(table, instrumentId);
							}
						}
					}
				}
			}
		}
	}
}

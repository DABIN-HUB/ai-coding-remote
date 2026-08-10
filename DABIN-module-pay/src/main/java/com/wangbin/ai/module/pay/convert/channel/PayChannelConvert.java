package com.wangbin.ai.module.pay.convert.channel;

import com.wangbin.ai.framework.common.pojo.PageResult;
import com.wangbin.ai.framework.common.util.collection.CollectionUtils;
import com.wangbin.ai.module.pay.controller.admin.channel.vo.PayChannelCreateReqVO;
import com.wangbin.ai.module.pay.controller.admin.channel.vo.PayChannelRespVO;
import com.wangbin.ai.module.pay.controller.admin.channel.vo.PayChannelUpdateReqVO;
import com.wangbin.ai.module.pay.dal.dataobject.channel.PayChannelDO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
public interface PayChannelConvert {

    PayChannelConvert INSTANCE = Mappers.getMapper(PayChannelConvert.class);

    @Mapping(target = "config",ignore = true)
    PayChannelDO convert(PayChannelCreateReqVO bean);

    @Mapping(target = "config",ignore = true)
    PayChannelDO convert(PayChannelUpdateReqVO bean);

    @Mapping(target = "config",expression = "java(com.wangbin.ai.framework.common.util.json.JsonUtils.toJsonString(bean.getConfig()))")
    PayChannelRespVO convert(PayChannelDO bean);

    default List<PayChannelRespVO> convertList(List<PayChannelDO> list) {
        // 列表不下发 config（密钥/证书），详情接口再返回完整配置
        return CollectionUtils.convertList(list, channel -> {
            PayChannelRespVO vo = convert(channel);
            vo.setConfig(null);
            return vo;
        });
    }

    PageResult<PayChannelRespVO> convertPage(PageResult<PayChannelDO> page);

}

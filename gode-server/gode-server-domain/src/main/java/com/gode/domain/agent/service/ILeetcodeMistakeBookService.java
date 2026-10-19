package com.gode.domain.agent.service;

import com.gode.domain.agent.model.valobj.mistake.MistakeBookPageVO;
import com.gode.domain.agent.model.valobj.mistake.MistakeDetailVO;
import com.gode.domain.agent.model.valobj.mistake.SaveMistakeCommand;

public interface ILeetcodeMistakeBookService {
    /** 同一用户、题号更新主记录，每次保存追加一条复盘历史。 */
    Long saveMistake(String userId, SaveMistakeCommand command);

    /** page 从 1 开始，默认每页 10 条。status 为空表示全部。 */
    MistakeBookPageVO queryPage(String userId, String status, String tag,
                              String keyword, Integer page, Integer pageSize);

    /** 校验归属，返回主记录、最新复盘及倒序完整历史列表。 */
    MistakeDetailVO queryDetail(String userId, Long mistakeId);

    /** 开始复习不累计完成次数。 */
    void startReview(String userId, Long mistakeId);

    /** 标记掌握时累计一次；已经掌握则不重复累计。 */
    void markMastered(String userId, Long mistakeId);

    /** 删除用户自己的错题及全部关联复盘；失败时一起回滚。 */
    void deleteMistake(String userId, Long mistakeId);
}

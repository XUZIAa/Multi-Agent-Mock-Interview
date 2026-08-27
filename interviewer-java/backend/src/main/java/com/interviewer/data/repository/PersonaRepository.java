package com.interviewer.data.repository;

import com.interviewer.core.error.ConfigException;
import com.interviewer.data.UtcStamp;
import com.interviewer.data.entity.PersonaRow;
import com.interviewer.data.mapper.PersonaMapper;
import com.interviewer.domain.persona.BuiltinPersonas;
import com.interviewer.domain.persona.PersonaContract;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Repository;

/** 人设仓储。内置人设首次启动时播种，之后只由用户增删改。 */
@Repository
public class PersonaRepository {

    private static final Logger log = LoggerFactory.getLogger(PersonaRepository.class);

    private final PersonaMapper mapper;
    private final JsonCodec codec;

    public PersonaRepository(PersonaMapper mapper, JsonCodec codec) {
        this.mapper = mapper;
        this.codec = codec;
    }

    /**
     * 播种内置人设。
     *
     * <p>只在空库时做：用户可能删掉了不喜欢的内置人设，每次启动都塞回去等于不让人删。
     *
     * <p>挂在就绪事件而不是 @PostConstruct：建表要先跑完，而且 @PostConstruct 阶段
     * 事务代理还没建立。这里每条 insert 各自原子，本来也不需要包事务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedBuiltins() {
        if (mapper.count() > 0) {
            return;
        }
        BuiltinPersonas.all().forEach(this::save);
        log.info("已播种 {} 个内置人设", BuiltinPersonas.all().size());
    }

    public List<PersonaContract> listAll() {
        List<PersonaContract> out = new ArrayList<>();
        for (PersonaRow row : mapper.listAll()) {
            PersonaContract contract = codec.read(row.getPayload(), PersonaContract.class);
            if (contract == null) {
                continue;
            }
            // 库里的 id 才是权威的，payload 里那份可能是复制时留下的旧值
            contract.setId(row.getId());
            out.add(contract);
        }
        return out;
    }

    public PersonaContract save(PersonaContract contract) {
        LocalDateTime now = UtcStamp.now();
        PersonaRow row = new PersonaRow();
        row.setId(contract.getId());
        row.setName(contract.getName());
        row.setArchetype(contract.getArchetype().value());
        row.setIsBuiltin(contract.isBuiltin());
        row.setPayload(codec.write(contract));
        row.setUpdatedAt(now);

        if (contract.getId() == null) {
            requireFreeName(contract.getName(), null);
            row.setUsageCount(0);
            row.setCreatedAt(now);
            mapper.insert(row);
            contract.setId(row.getId());
        } else {
            requireFreeName(contract.getName(), contract.getId());
            mapper.updateById(row);
        }
        return contract;
    }

    /**
     * 名字唯一由数据库约束保证，但撞了会抛 SQL 异常，界面只能显示「请求失败」。
     * 提前查一次，换成能看懂的话。
     */
    private void requireFreeName(String name, Integer selfId) {
        PersonaRow existing = mapper.findByName(name);
        if (existing != null && !existing.getId().equals(selfId)) {
            throw new ConfigException("人设名重复: " + name,
                    "已经有一个叫「" + name + "」的人设了，换个名字");
        }
    }

    public void delete(int personaId) {
        PersonaRow row = mapper.selectById(personaId);
        if (row == null) {
            return;
        }
        if (Boolean.TRUE.equals(row.getIsBuiltin())) {
            throw new ConfigException("内置人设不可删除",
                    "内置人设不能删除，可以复制一份再改");
        }
        mapper.deleteById(personaId);
    }

    /**
     * 生成一个不重名的名字：「暴躁 CTO」→「暴躁 CTO 副本」→「暴躁 CTO 副本 2」。
     *
     * <p>复制人设与新建人设都要用它。少了这一步，第二次新建就会撞唯一约束。
     */
    public String uniqueName(String base) {
        Set<String> taken = new LinkedHashSet<>();
        mapper.allNames().forEach(n -> taken.add(n.strip().toLowerCase(Locale.ROOT)));
        String stem = base == null || base.isBlank() ? "新人设" : base.strip();
        if (!taken.contains(stem.toLowerCase(Locale.ROOT))) {
            return stem;
        }
        String copy = stem + " 副本";
        if (!taken.contains(copy.toLowerCase(Locale.ROOT))) {
            return copy;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = copy + " " + i;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return copy + " " + System.currentTimeMillis();
    }
}

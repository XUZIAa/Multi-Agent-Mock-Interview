package com.interviewer.rpc;

import com.interviewer.data.repository.PersonaRepository;
import com.interviewer.domain.persona.PersonaContract;
import com.interviewer.rpc.dto.Ok;
import com.interviewer.rpc.dto.UniqueNameBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "personas")
public class PersonaController {

    private final PersonaRepository personas;

    public PersonaController(PersonaRepository personas) {
        this.personas = personas;
    }

    @GetMapping("/personas")
    public List<PersonaContract> list() {
        return personas.listAll();
    }

    @PostMapping("/personas")
    public PersonaContract save(@Valid @RequestBody PersonaContract contract) {
        return personas.save(contract);
    }

    @DeleteMapping("/personas/{persona_id}")
    public Ok delete(@PathVariable("persona_id") int personaId) {
        personas.delete(personaId);
        return Ok.DONE;
    }

    @PostMapping("/personas/unique-name")
    public Map<String, String> uniqueName(@Valid @RequestBody UniqueNameBody body) {
        return Map.of("name", personas.uniqueName(body.base()));
    }
}

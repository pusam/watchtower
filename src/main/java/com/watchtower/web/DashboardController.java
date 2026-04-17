package com.watchtower.web;

import com.watchtower.registry.HostRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final HostRegistry registry;

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("hosts", registry.allHosts());
        return "dashboard";
    }
}

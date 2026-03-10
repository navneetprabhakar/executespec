package com.navneet.executespec.auth.security;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final AppUserRepository userRepo;
    private final OrgMemberRepository orgMemberRepo;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser user = userRepo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        OrgMember orgMember = null;
        if (user.getOrgId() != null) {
            orgMember = orgMemberRepo.findByOrgIdAndUserId(user.getOrgId(), user.getId()).orElse(null);
        }
        return new AppUserDetails(user, orgMember);
    }

    public UserDetails loadUserById(String userId) {
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found by id: " + userId));
        OrgMember orgMember = null;
        if (user.getOrgId() != null) {
            orgMember = orgMemberRepo.findByOrgIdAndUserId(user.getOrgId(), user.getId()).orElse(null);
        }
        return new AppUserDetails(user, orgMember);
    }
}

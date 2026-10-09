package main

func defaultDiscoveryPeers() []string {
	return []string{"tcp://225284.xyz:11010", "wss://et.vv1234.cn"}
}

func repairDiscoveryPeers(peers []string) []string {
	result := []string{}
	seen := map[string]bool{}
	for _, peer := range peers {
		choices := []string{peer}
		if peer == "tcp://public.easytier.top:11010" || peer == "tcp://public.easytier.cn:11010" {
			choices = defaultDiscoveryPeers()
		}
		for _, choice := range choices {
			if !seen[choice] {
				result = append(result, choice)
				seen[choice] = true
			}
		}
	}
	return result
}

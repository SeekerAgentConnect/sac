package report

import (
	"os/exec"
	"strings"
)

// run is one command, for the three facts about the machine a report needs that Go cannot answer:
// the processor's own name, how much memory it has, and the descriptor limit the shell is under.
//
// Every one of them is optional. A machine where `sysctl` is not `sysctl` prints a report without a
// processor name rather than failing, because the numbers are the point and the model is context.
func run(name string, argument ...string) (string, error) {
	output, err := exec.Command(name, argument...).Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(output)), nil
}

/* -*- Mode:C++; c-file-style:"gnu"; indent-tabs-mode:nil; -*- */
/**
 * Copyright (c) 2014-2016,  Regents of the University of California,
 *                           Arizona Board of Regents,
 *                           Colorado State University,
 *                           University Pierre & Marie Curie, Sorbonne University,
 *                           Washington University in St. Louis,
 *                           Beijing Institute of Technology,
 *                           The University of Memphis.
 *
 * This file is part of NFD (Named Data Networking Forwarding Daemon).
 * See AUTHORS.md for complete list of NFD authors and contributors.
 *
 * NFD is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * NFD is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * NFD, e.g., in COPYING.md file.  If not, see <http://www.gnu.org/licenses/>.
 */

#ifndef NFD_ANDROID_CUSTOM_LOGGER_FACTORY_HPP
#define NFD_ANDROID_CUSTOM_LOGGER_FACTORY_HPP

#include "core/common.hpp"

#include "core/config-file.hpp"
#include "core/logger.hpp"

namespace nfd {

class LoggerFactory : noncopyable
{
public:

  class Error : public std::runtime_error
  {
  public:
    explicit
    Error(const std::string& error)
      : std::runtime_error(error)
    {
    }
  };

  static LoggerFactory&
  getInstance();

  void
  setConfigFile(ConfigFile& config);

  void
  onConfig(const ConfigSection& section, bool isDryRun, const std::string& filename);

  std::list<std::string>
  getModules() const;

  static Logger&
  create(const std::string& moduleName);


PUBLIC_WITH_TESTS_ELSE_PRIVATE:

  // these methods are used during unit-testing

  LogLevel
  getDefaultLevel() const;

  void
  setDefaultLevel(LogLevel level);

private:

  LoggerFactory();

  Logger&
  createLogger(const std::string& moduleName);

  LogLevel
  parseLevel(const std::string& level);

  LogLevel
  extractLevel(const ConfigSection& item, const std::string& key);

private:

  typedef std::map<std::string, LogLevel> LevelMap;
  typedef std::pair<std::string, LogLevel> NameAndLevel;

  LevelMap m_levelNames;

  typedef std::map<std::string, Logger> LoggerMap;
  typedef std::pair<std::string, Logger> NameAndLogger;

  LoggerMap m_loggers;

  LogLevel m_defaultLevel;
};

inline LogLevel
LoggerFactory::getDefaultLevel() const
{
  return m_defaultLevel;
}

} // namespace nfd

#endif // NFD_ANDROID_CUSTOM_LOGGER_FACTORY_HPP
